package com.example.credit_system_kotlin.integration.ledger.repository

import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.SharedContainers
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.atomic.AtomicLong

/**
 * baseline이 실제 MySQL 에서 job 원장 중복을 막는지 확인한다. 제약이 엔티티에 매핑하지 않은 생성 컬럼에 걸려 있어 H2 에는 없다.
 * 트랜잭션 롤백이 없으므로 테스트마다 다른 jobId 를 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest
class LedgerJobGuardMigrationTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        private val nextJobId = AtomicLong(1_000L)

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "ledger_job_guard_migration")
        }
    }

    private var userId: Long = 0
    private var jobId: Long = 0

    @BeforeEach
    fun setUp() {
        userId = userRepository.save(User("ledger-guard", 0L)).persistedId
        jobId = nextJobId.incrementAndGet()
    }

    @Test
    fun `같은 job 에 CONFIRM 을 두 번 쓰면 위반이다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.confirm(userId, jobId))

        assertThatThrownBy { ledgerRepository.saveAndFlush(LedgerEntry.confirm(userId, jobId)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 job 에 REFUND 를 두 번 쓰면 위반이다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.refund(userId, jobId, 100L))

        assertThatThrownBy { ledgerRepository.saveAndFlush(LedgerEntry.refund(userId, jobId, 100L)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 job 에 CONFIRM 뒤 REFUND 를 쓰면 종결 원장 제약이 막는다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.confirm(userId, jobId))

        // 유형이 달라 (job_id, type) 키는 걸리지 않는다. 생성 컬럼의 유니크만 이 경우를 막는다.
        assertThatThrownBy { ledgerRepository.saveAndFlush(LedgerEntry.refund(userId, jobId, 100L)) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
            .hasMessageContaining("uk_ledger_terminal_job")
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(userId)).hasSize(1)
    }

    @Test
    fun `HOLD 뒤 CONFIRM 은 정상이다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.hold(userId, jobId, 100L))
        ledgerRepository.saveAndFlush(LedgerEntry.confirm(userId, jobId))

        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(userId)).hasSize(2)
    }

    @Test
    fun `HOLD 뒤 REFUND 는 정상이다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.hold(userId, jobId, 100L))
        ledgerRepository.saveAndFlush(LedgerEntry.refund(userId, jobId, 100L))

        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(userId)).hasSize(2)
    }

    @Test
    fun `job 이 없는 ADMIN_GRANT 와 CHARGE 는 여러 행을 쓸 수 있다`() {
        ledgerRepository.saveAndFlush(LedgerEntry.adminGrant(userId, "guard-grant-1", 100L))
        ledgerRepository.saveAndFlush(LedgerEntry.adminGrant(userId, "guard-grant-2", 100L))
        ledgerRepository.saveAndFlush(LedgerEntry.charge(userId, "guard-charge-1", 100L))
        ledgerRepository.saveAndFlush(LedgerEntry.charge(userId, "guard-charge-2", 100L))

        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(userId)).hasSize(4)
    }
}
