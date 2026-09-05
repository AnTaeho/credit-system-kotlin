package com.example.credit_system_kotlin.ledger.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class LedgerReconciliationTaskTest @Autowired constructor(
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val eventPublisher: ApplicationEventPublisher = mock()

    private val task = LedgerReconciliationTask(ledgerRepository, eventPublisher)

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

    @Test
    fun `일치 2건 불일치 1건이면 이벤트로 checkedCount 3 mismatchCount 1을 발행한다`() {
        val ok1 = organizationRepository.save(Organization("acme-1", 1000L))
        val ok2 = organizationRepository.save(Organization("acme-2", 1000L))
        val bad = organizationRepository.save(Organization("acme-3", 1000L))
        ledgerRepository.save(LedgerEntry.charge(ok1.persistedId, "charge-key-ok-1", 500L))
        organizationRepository.addBalance(ok1.persistedId, 500L, Instant.now())
        ledgerRepository.save(LedgerEntry.charge(ok2.persistedId, "charge-key-ok-2", 300L))
        organizationRepository.addBalance(ok2.persistedId, 300L, Instant.now())
        organizationRepository.addBalance(bad.persistedId, 1L, Instant.now())
        organizationRepository.flush()

        task.reconcile()

        val captor = argumentCaptor<LedgerReconciliationCompleted>()
        verify(eventPublisher).publishEvent(captor.capture())
        assertThat(captor.firstValue.checkedCount).isEqualTo(3)
        assertThat(captor.firstValue.mismatchCount).isEqualTo(1)
    }

    private fun errorLogs(): List<ILoggingEvent> =
        logAppender.list.filter { it.level == Level.ERROR }
}
