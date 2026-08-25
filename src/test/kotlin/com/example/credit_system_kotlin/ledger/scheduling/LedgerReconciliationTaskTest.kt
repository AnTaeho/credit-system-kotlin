package com.example.credit_system_kotlin.ledger.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class LedgerReconciliationTaskTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val task = LedgerReconciliationTask(ledgerRepository)

    private lateinit var taskLogger: Logger
    private lateinit var logAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        logAppender = ListAppender<ILoggingEvent>()
        logAppender.start()
        taskLogger = LoggerFactory.getLogger(LedgerReconciliationTask::class.java) as Logger
        taskLogger.addAppender(logAppender)
    }

    @AfterEach
    fun tearDown() {
        taskLogger.detachAppender(logAppender)
    }

    @Test
    fun `원장과 잔액이 맞으면 아무 경보도 남기지 않는다`() {
        organizationRepository.save(Organization("acme", 1000L))

        task.reconcile()

        assertThat(errorLogs()).isEmpty()
    }

    @Test
    fun `충전과 hold가 반영된 조직도 대사를 통과한다`() {
        val org = organizationRepository.save(Organization("acme", 1000L))
        ledgerRepository.save(LedgerEntry.charge(org.persistedId, "charge-key-2", 500L))
        ledgerRepository.save(LedgerEntry.hold(org.persistedId, 1L, 100L))
        organizationRepository.addBalance(org.persistedId, 400L, Instant.now())
        organizationRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).isEmpty()
    }

    @Test
    fun `잔액이 원장과 어긋나면 ERROR로 경보한다`() {
        val org = organizationRepository.save(Organization("acme", 1000L))
        ledgerRepository.save(LedgerEntry.charge(org.persistedId, "charge-key-3", 500L))
        organizationRepository.addBalance(org.persistedId, 999L, Instant.now())
        organizationRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("organizationId=${org.persistedId}")
    }

    @Test
    fun `원장 항목이 없는 조직도 검사 대상에 포함된다`() {
        val org = organizationRepository.save(Organization("acme", 1000L))
        organizationRepository.addBalance(org.persistedId, 1L, Instant.now())
        organizationRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("organizationId=${org.persistedId}")
    }

    @Test
    fun `배치 크기를 넘는 조직도 모두 검사한다`() {
        val mismatchIndex = 119
        var mismatchOrg: Organization? = null
        for (i in 0 until 205) {
            val org = organizationRepository.save(Organization("org-$i", 1000L))
            if (i == mismatchIndex) {
                mismatchOrg = org
            }
        }
        val mismatchOrgId = requireNotNull(mismatchOrg).persistedId
        organizationRepository.addBalance(mismatchOrgId, 1L, Instant.now())
        organizationRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("organizationId=$mismatchOrgId")
    }

    private fun errorLogs(): List<ILoggingEvent> =
        logAppender.list.filter { it.level == Level.ERROR }
}
