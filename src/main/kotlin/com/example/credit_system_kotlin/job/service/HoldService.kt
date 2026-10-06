package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.global.exception.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.global.exception.IdempotencyKeyReusedException
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
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

private val log = LoggerFactory.getLogger(HoldService::class.java)

/** 생성 요청을 받아 크레딧을 먼저 잡아 둔다. 실제 생성은 워커가 HOLDING job 을 집어 가서 한다. */
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

    /**
     * 멱등키 저장, 잔액 차감, job 생성, HOLD 원장 기록을 한 트랜잭션에 묶는다. 중간에 터지면 차감도 같이 되돌아간다.
     * 같은 키가 이미 있으면 차감 없이 기존 job 을 돌려준다.
     */
    @Transactional
    fun requestGeneration(userId: Long, idemKey: String, prompt: String): HoldResult {
        validateRequest(idemKey, prompt)

        val requestHash = sha256Hex(prompt)
        val existing = idempotencyKeyRepository.findByUserIdAndIdemKey(userId, idemKey)
        if (existing != null) {
            // 같은 키에 다른 내용이면 기존 job 을 돌려줄 수 없다. 해시가 없는 옛 키는 비교하지 않는다.
            if (existing.requestHash != null && existing.requestHash != requestHash) {
                eventPublisher.publishEvent(DefenseTriggered(DefensePoint.IDEM_KEY, DefenseOutcome.MISMATCH))
                log.info("멱등키 재사용 거절: userId={}, idemKey={}", userId, idemKey)
                throw IdempotencyKeyReusedException()
            }
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.IDEM_KEY, DefenseOutcome.APP_HIT))
            return resolveDuplicateRequest(existing)
        }

        idempotencyKeyRepository.save(IdempotencyKey(userId, idemKey, requestHash))

        val cost = appProperties.generation.cost

        deductBalance(userId, cost)
        val job = jobRepository.save(Job.hold(userId, cost, prompt))
        val jobId = job.persistedId

        attachIdemKeyToJob(userId, idemKey, jobId)

        ledgerRepository.save(LedgerEntry.hold(userId, jobId, cost))
        log.info("hold 완료: userId={}, jobId={}, cost={}", userId, jobId, cost)
        return HoldResult(jobId, false)
    }

    /** 차감 전에 걸러낸다. prompt 는 비어 있으면 안 되고 1000자까지다. */
    private fun validateRequest(idemKey: String, prompt: String) {
        validateIdemKey(idemKey)
        if (prompt.isBlank()) {
            throw InvalidRequestException("prompt는 필수입니다.")
        }
        if (prompt.length > 1000) {
            throw InvalidRequestException("prompt는 1000자를 초과할 수 없습니다.")
        }
    }

    /** 기존 job id 를 중복 표시와 함께 돌려준다. 키에 job 이 아직 안 붙었으면 처리 중으로 보고 거절한다. */
    private fun resolveDuplicateRequest(existing: IdempotencyKey): HoldResult {
        val jobId = existing.jobId ?: throw DuplicateRequestInProgressException()
        log.info("중복 요청 감지: jobId={}", jobId)
        return HoldResult(jobId, true)
    }

    /** 잔액이 모자라면 UPDATE 가 0행이다. 그때만 사용자를 다시 읽어 현재 잔액을 예외에 싣는다. */
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

    /** 1행이 아니면 예외로 트랜잭션을 되돌린다. 키가 job 없이 남으면 같은 키의 재요청이 계속 거절된다. */
    private fun attachIdemKeyToJob(userId: Long, idemKey: String, jobId: Long) {
        val attached = idempotencyKeyRepository.attachJobId(userId, idemKey, jobId)
        check(attached == 1) {
            "idempotency key에 jobId 연결 실패: userId=$userId, idemKey=$idemKey, jobId=$jobId"
        }
    }
}

/** 멱등키에 묶을 요청 내용의 지문. UTF-8 바이트의 SHA-256 을 소문자 hex 64자로 낸다. */
internal fun sha256Hex(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
