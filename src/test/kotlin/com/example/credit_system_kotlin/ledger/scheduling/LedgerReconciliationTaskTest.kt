package com.example.credit_system_kotlin.ledger.scheduling

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
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
    private val userRepository: UserRepository,
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
        userRepository.save(User("acme", 1000L))

        task.reconcile()

        assertThat(errorLogs()).isEmpty()
    }

    @Test
    fun `충전과 hold가 반영된 사용자도 대사를 통과한다`() {
        val user = userRepository.save(User("acme", 1000L))
        ledgerRepository.save(LedgerEntry.charge(user.persistedId, "charge-key-2", 500L))
        ledgerRepository.save(LedgerEntry.hold(user.persistedId, 1L, 100L))
        userRepository.addBalance(user.persistedId, 400L, Instant.now())
        userRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).isEmpty()
    }

    @Test
    fun `잔액이 원장과 어긋나면 ERROR로 경보한다`() {
        val user = userRepository.save(User("acme", 1000L))
        ledgerRepository.save(LedgerEntry.charge(user.persistedId, "charge-key-3", 500L))
        userRepository.addBalance(user.persistedId, 999L, Instant.now())
        userRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("userId=${user.persistedId}")
    }

    @Test
    fun `원장 항목이 없는 사용자도 검사 대상에 포함된다`() {
        val user = userRepository.save(User("acme", 1000L))
        userRepository.addBalance(user.persistedId, 1L, Instant.now())
        userRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("userId=${user.persistedId}")
    }

    @Test
    fun `배치 크기를 넘는 사용자도 모두 검사한다`() {
        val mismatchIndex = 119
        var mismatchUser: User? = null
        for (i in 0 until 205) {
            val user = userRepository.save(User("user-$i", 1000L))
            if (i == mismatchIndex) {
                mismatchUser = user
            }
        }
        val mismatchUserId = requireNotNull(mismatchUser).persistedId
        userRepository.addBalance(mismatchUserId, 1L, Instant.now())
        userRepository.flush()

        task.reconcile()

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage).contains("userId=$mismatchUserId")
    }

    @Test
    fun `일치 2건 불일치 1건이면 이벤트로 checkedCount 3 mismatchCount 1을 발행한다`() {
        val ok1 = userRepository.save(User("acme-1", 1000L))
        val ok2 = userRepository.save(User("acme-2", 1000L))
        val bad = userRepository.save(User("acme-3", 1000L))
        ledgerRepository.save(LedgerEntry.charge(ok1.persistedId, "charge-key-ok-1", 500L))
        userRepository.addBalance(ok1.persistedId, 500L, Instant.now())
        ledgerRepository.save(LedgerEntry.charge(ok2.persistedId, "charge-key-ok-2", 300L))
        userRepository.addBalance(ok2.persistedId, 300L, Instant.now())
        userRepository.addBalance(bad.persistedId, 1L, Instant.now())
        userRepository.flush()

        task.reconcile()

        val captor = argumentCaptor<LedgerReconciliationCompleted>()
        verify(eventPublisher).publishEvent(captor.capture())
        assertThat(captor.firstValue.checkedCount).isEqualTo(3)
        assertThat(captor.firstValue.mismatchCount).isEqualTo(1)
    }

    private fun errorLogs(): List<ILoggingEvent> =
        logAppender.list.filter { it.level == Level.ERROR }
}
