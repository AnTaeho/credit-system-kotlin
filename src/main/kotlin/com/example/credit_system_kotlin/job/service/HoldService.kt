package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(HoldService::class.java)

@Service
class HoldService(
    private val organizationRepository: OrganizationRepository,
    private val jobRepository: JobRepository,
    private val appProperties: AppProperties
) {

    @Transactional
    fun requestGeneration(organizationId: Long, prompt: String): HoldResult {
        val cost = appProperties.generation.cost
        val organization = organizationRepository.findByIdOrNull(organizationId)
            ?: error("조직을 찾을 수 없습니다: organizationId=$organizationId")
        organization.deduct(cost)

        val job = jobRepository.save(Job.hold(organizationId, cost, prompt))
        log.info("hold 완료: organizationId={}, jobId={}, cost={}", organizationId, job.persistedId, cost)
        return HoldResult(job.persistedId)
    }
}
