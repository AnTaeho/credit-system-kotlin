package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface JobRepository : JpaRepository<Job, Long> {

    fun findByStatusOrderByIdAsc(status: JobStatus, pageable: Pageable): List<Job>

    fun findByOrganizationIdOrderByIdDesc(organizationId: Long): List<Job>
}
