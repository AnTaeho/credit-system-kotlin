package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.global.exception.JobNotFoundException
import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.dto.JobResponse
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class JobQueryService(private val jobRepository: JobRepository) {

    @Transactional(readOnly = true)
    fun findByUser(userId: Long, request: CursorRequest): CursorPage<JobResponse> {
        val limit = PageRequest.of(0, request.fetchSize)
        val fetched = request.cursor
            ?.let { jobRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, it, limit) }
            ?: jobRepository.findByUserIdOrderByIdDesc(userId, limit)
        return CursorPage.of(fetched, request.size, Job::persistedId, JobResponse::from)
    }

    /** 남의 job 도 없는 job 과 같이 [JobNotFoundException] 이다. 존재 여부를 흘리지 않는다. */
    @Transactional(readOnly = true)
    fun findOne(userId: Long, jobId: Long): JobResponse =
        jobRepository.findByIdAndUserId(jobId, userId)
            ?.let(JobResponse::from)
            ?: throw JobNotFoundException(jobId)
}
