package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.AppProperties
import com.example.credit_system_kotlin.global.DuplicateRequestInProgressException
import com.example.credit_system_kotlin.global.InsufficientBalanceException
import com.example.credit_system_kotlin.global.InvalidRequestException
import com.example.credit_system_kotlin.global.OrganizationNotFoundException
import com.example.credit_system_kotlin.global.validateIdemKey
import com.example.credit_system_kotlin.job.api.HoldResult
import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import com.example.credit_system_kotlin.job.domain.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobRepository
import com.example.credit_system_kotlin.ledger.LedgerEntry
import com.example.credit_system_kotlin.ledger.LedgerRepository
import com.example.credit_system_kotlin.ledger.LedgerType
import com.example.credit_system_kotlin.organization.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(HoldService::class.java)

@Service
class HoldService(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val organizationRepository: OrganizationRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val appProperties: AppProperties
) {

    @Transactional
    fun requestGeneration(organizationId: Long, idemKey: String, prompt: String): HoldResult {
        validateRequest(idemKey, prompt)

        val existing = idempotencyKeyRepository.findByOrganizationIdAndIdemKey(organizationId, idemKey)
        if (existing != null) {
            return resolveDuplicateRequest(existing)
        }

        idempotencyKeyRepository.save(IdempotencyKey(organizationId, idemKey))

        val cost = appProperties.generation.cost

        deductBalance(organizationId, cost)
        val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
        val jobId = job.persistedId

        attachIdemKeyToJob(organizationId, idemKey, jobId)

        ledgerRepository.save(LedgerEntry.of(organizationId, jobId, LedgerType.HOLD, -cost))
        log.info("hold 완료: organizationId={}, jobId={}, cost={}", organizationId, jobId, cost)
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

    private fun deductBalance(organizationId: Long, cost: Long) {
        val updated = organizationRepository.deductBalance(organizationId, cost, Instant.now())
        if (updated == 1) {
            return
        }

        val organization = organizationRepository.findByIdOrNull(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
        throw InsufficientBalanceException(organization.balance, cost)
    }

    private fun attachIdemKeyToJob(organizationId: Long, idemKey: String, jobId: Long) {
        val attached = idempotencyKeyRepository.attachJobId(organizationId, idemKey, jobId)
        check(attached == 1) {
            "idempotency key에 jobId 연결 실패: organizationId=$organizationId, idemKey=$idemKey, jobId=$jobId"
        }
    }
}
