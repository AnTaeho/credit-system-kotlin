package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.validation.validateIdemKey
import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.repository.UserRepository
import com.example.credit_system_kotlin.user.service.UserFinder
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(HoldService::class.java)

@Service
class HoldService(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val userRepository: UserRepository,
    private val userFinder: UserFinder,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val appProperties: AppProperties,
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
    fun requestGeneration(userId: Long, idemKey: String, prompt: String): HoldResult {
        validateRequest(idemKey, prompt)

        val existing = idempotencyKeyRepository.findByUserIdAndIdemKey(userId, idemKey)
        if (existing != null) {
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.IDEM_KEY, DefenseOutcome.APP_HIT))
            return resolveDuplicateRequest(existing)
        }

        idempotencyKeyRepository.save(IdempotencyKey(userId, idemKey))

        val cost = appProperties.generation.cost

        deductBalance(userId, cost)
        val job = jobRepository.save(Job.hold(userId, cost, prompt))
        val jobId = job.persistedId

        attachIdemKeyToJob(userId, idemKey, jobId)

        ledgerRepository.save(LedgerEntry.hold(userId, jobId, cost))
        // 이 한 줄이 요청 ID 와 jobId 를 잇는 자리다. 아직 HTTP 스레드 위라 MDC 에 requestId 가 있고,
        // 로그 패턴이 그것을 줄 앞에 붙인다. 여기서 태어난 jobId 를 같은 줄이 찍으므로,
        // "요청 ID → jobId" 의 연결이 이 줄 하나에 남고 그 뒤 워커·회수 로그는 jobId 로 따라간다.
        // 요청 ID 자체를 워커까지 들고 가지 않는 이유는 LogContext 문서에 적었다(jobs 컬럼 추가 대비 값어치).
        log.info("hold 완료: userId={}, jobId={}, cost={}", userId, jobId, cost)
        return HoldResult(jobId, false)
    }

    private fun validateRequest(idemKey: String, prompt: String) {
        validateIdemKey(idemKey)
        if (prompt.isBlank()) {
            throw InvalidRequestException("prompt는 필수입니다.")
        }
        if (prompt.length > 1000) {
            throw InvalidRequestException("prompt는 1000자를 초과할 수 없습니다.")
        }
    }

    private fun resolveDuplicateRequest(existing: IdempotencyKey): HoldResult {
        val jobId = existing.jobId ?: throw DuplicateRequestInProgressException()
        log.info("중복 요청 감지: jobId={}", jobId)
        return HoldResult(jobId, true)
    }

    private fun deductBalance(userId: Long, cost: Long) {
        val updated = userRepository.deductBalance(userId, cost, Instant.now())
        if (updated == 1) {
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.HOLD_BALANCE, DefenseOutcome.APPLIED))
            return
        }
        eventPublisher.publishEvent(DefenseTriggered(DefensePoint.HOLD_BALANCE, DefenseOutcome.REJECTED))

        val user = userFinder.getOrThrow(userId)
        throw InsufficientBalanceException(user.balance, cost)
    }

    private fun attachIdemKeyToJob(userId: Long, idemKey: String, jobId: Long) {
        val attached = idempotencyKeyRepository.attachJobId(userId, idemKey, jobId)
        check(attached == 1) {
            "idempotency key에 jobId 연결 실패: userId=$userId, idemKey=$idemKey, jobId=$jobId"
        }
    }
}
