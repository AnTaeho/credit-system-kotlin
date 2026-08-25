package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.dto.JobResponse
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JobQueryService(private val jobRepository: JobRepository) {

    @Transactional(readOnly = true)
    fun findByOrganization(organizationId: Long): List<JobResponse> =
        jobRepository.findByOrganizationIdOrderByIdDesc(organizationId)
            .map(JobResponse::from)
}
