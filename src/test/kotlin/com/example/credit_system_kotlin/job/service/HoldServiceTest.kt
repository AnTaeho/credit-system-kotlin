package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.exception.IdempotencyKeyReusedException
import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import com.example.credit_system_kotlin.user.service.UserFinder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class HoldServiceTest @Autowired constructor(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val eventPublisher = RecordingEventPublisher()

    private val holdService = HoldService(
        idempotencyKeyRepository, userRepository, UserFinder(userRepository),
        jobRepository, ledgerRepository,
        appProperties(), eventPublisher
    )

    /** 프로퍼티 초기화 자리에서 save 하면 트랜잭션 밖에서 커밋되어 롤백되지 않는다. 엔티티는 @BeforeEach 에서 준비한다. */
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("acme", 1000L))
        eventPublisher.clear()
    }

    @Test
    fun `정상 요청은 잔액을 차감하고 job과 ledger를 생성한다`() {
        val result = holdService.requestGeneration(user.persistedId, "key-1", "a cat")

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(result.duplicate).isFalse()
        assertThat(found.balance).isEqualTo(900L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.REJECTED)).isZero()
    }

    @Test
    fun `동일 idemKey로 재요청하면 같은 job을 반환하고 잔액이 추가로 차감되지 않는다`() {
        val first = holdService.requestGeneration(user.persistedId, "key-1", "a cat")
        val second = holdService.requestGeneration(user.persistedId, "key-1", "a cat")

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(second.duplicate).isTrue()
        assertThat(second.jobId).isEqualTo(first.jobId)
        assertThat(found.balance).isEqualTo(900L)
        assertThat(eventPublisher.countOf(DefensePoint.IDEM_KEY, DefenseOutcome.APP_HIT)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED)).isEqualTo(1)
    }

    @Test
    fun `잔액이 부족하면 예외가 발생하고 job이 생성되지 않는다`() {
        val poor = userRepository.save(User("poor", 50L))

        assertThatThrownBy { holdService.requestGeneration(poor.persistedId, "key-2", "a cat") }
            .isInstanceOf(InsufficientBalanceException::class.java)

        assertThat(jobRepository.findByUserIdOrderByIdDesc(poor.persistedId)).isEmpty()
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(poor.persistedId)).isEmpty()
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.REJECTED)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED)).isZero()
    }

    @Test
    fun `필수값이 없거나 길이 제한을 넘으면 요청을 거부한다`() {
        assertThatThrownBy { holdService.requestGeneration(user.persistedId, " ", "cat") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 필수입니다.")
        assertThatThrownBy {
            holdService.requestGeneration(user.persistedId, "key", "a".repeat(1001))
        }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("prompt는 1000자를 초과할 수 없습니다.")

        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    @Test
    fun `idemKey와 prompt의 최대 길이는 허용한다`() {
        val result = holdService.requestGeneration(
            user.persistedId, "k".repeat(100), "p".repeat(1000)
        )

        assertThat(result.duplicate).isFalse()
        assertThat(jobRepository.findById(result.jobId).orElseThrow().prompt).hasSize(1000)
    }

    @Test
    fun `idemKey가 최대 길이를 넘으면 어떤 데이터도 변경하지 않는다`() {
        assertThatThrownBy {
            holdService.requestGeneration(user.persistedId, "k".repeat(101), "cat")
        }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 100자를 초과할 수 없습니다.")

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(1000L)
        assertThat(idempotencyKeyRepository.count()).isZero()
        assertThat(jobRepository.count()).isZero()
        assertThat(ledgerRepository.count()).isZero()
    }

    @Test
    fun `존재하지 않는 사용자의 생성 요청이면 예외가 발생한다`() {
        val missingUserId = user.persistedId + 999_999L

        assertThatThrownBy {
            holdService.requestGeneration(missingUserId, "key-3", "a cat")
        }
            .isInstanceOf(UserNotFoundException::class.java)
            .hasMessage("존재하지 않는 user: $missingUserId")

        assertThat(jobRepository.count()).isZero()
        assertThat(ledgerRepository.count()).isZero()
    }

    @Test
    fun `새 멱등키에는 prompt 의 SHA-256 hex 가 함께 저장된다`() {
        holdService.requestGeneration(user.persistedId, "key-1", "a cat")

        val stored = requireNotNull(idempotencyKeyRepository.findByUserIdAndIdemKey(user.persistedId, "key-1"))
        // printf 'a cat' | shasum -a 256
        assertThat(stored.requestHash).isEqualTo("51e467415607798220a3776f6ae1a2a09ddc7e5dcdc955d685477b4cf05ade22")
    }

    @Test
    fun `같은 idemKey에 다른 prompt면 거절하고 잔액 job 원장을 바꾸지 않는다`() {
        val first = holdService.requestGeneration(user.persistedId, "key-1", "a cat")
        eventPublisher.clear()

        assertThatThrownBy { holdService.requestGeneration(user.persistedId, "key-1", "a dog") }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance).isEqualTo(900L)
        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).extracting("id")
            .containsExactly(first.jobId)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(1)
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.IDEM_KEY, DefenseOutcome.MISMATCH)).isEqualTo(1)
        assertThat(eventPublisher.countOf(DefensePoint.IDEM_KEY, DefenseOutcome.APP_HIT)).isZero()
        assertThat(eventPublisher.countOf(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED)).isZero()
    }

    @Test
    fun `내용 해시가 없는 옛 멱등키는 prompt가 달라도 기존 job을 돌려준다`() {
        idempotencyKeyRepository.save(IdempotencyKey(user.persistedId, "legacy-key"))
        val legacyJob = jobRepository.save(Job.hold(user.persistedId, 100L, "a cat"))
        idempotencyKeyRepository.attachJobId(user.persistedId, "legacy-key", legacyJob.persistedId)

        val result = holdService.requestGeneration(user.persistedId, "legacy-key", "a dog")

        assertThat(result.duplicate).isTrue()
        assertThat(result.jobId).isEqualTo(legacyJob.persistedId)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance).isEqualTo(1000L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
        assertThat(eventPublisher.countOf(DefensePoint.IDEM_KEY, DefenseOutcome.APP_HIT)).isEqualTo(1)
    }
}
