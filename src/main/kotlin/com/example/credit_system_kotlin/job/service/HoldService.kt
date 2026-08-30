package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.exception.InsufficientBalanceException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.service.OrganizationFinder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(HoldService::class.java)

@Service
class HoldService(
    private val organizationRepository: OrganizationRepository,
    private val organizationFinder: OrganizationFinder,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val appProperties: AppProperties
) {

    @Transactional
    fun requestGeneration(organizationId: Long, prompt: String): HoldResult {
        validateRequest(prompt)

        val cost = appProperties.generation.cost
        deductBalance(organizationId, cost)

        val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
        val jobId = job.persistedId

        ledgerRepository.save(LedgerEntry.hold(organizationId, jobId, cost))
        log.info("hold 완료: organizationId={}, jobId={}, cost={}", organizationId, jobId, cost)
        return HoldResult(jobId)
    }

    private fun validateRequest(prompt: String) {
        if (prompt.isBlank()) {
            throw InvalidRequestException("prompt는 필수입니다.")
        }
        if (prompt.length > 1000) {
            throw InvalidRequestException("prompt는 1000자를 초과할 수 없습니다.")
        }
    }

    private fun deductBalance(organizationId: Long, cost: Long) {
        val updated = organizationRepository.deductBalance(organizationId, cost, Instant.now())
        if (updated == 1) {
            return
        }

        val organization = organizationFinder.getOrThrow(organizationId)
        throw InsufficientBalanceException(organization.balance, cost)
    }
}
