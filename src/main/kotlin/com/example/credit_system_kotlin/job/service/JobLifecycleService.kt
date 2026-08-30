package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(JobLifecycleService::class.java)

@Service
class JobLifecycleService(
    private val jobRepository: JobRepository
) {

    @Transactional
    fun startProcessing(jobId: Long) {
        val job = getJob(jobId)
        job.startProcessing()
    }

    @Transactional
    fun confirm(jobId: Long, resultUrl: String) {
        val job = getJob(jobId)
        job.complete(resultUrl)
        log.info("confirm 완료: jobId={}", jobId)
    }

    @Transactional
    fun markFailed(jobId: Long) {
        val job = getJob(jobId)
        job.fail()
        log.info("실패 처리: jobId={}", jobId)
    }

    private fun getJob(jobId: Long): Job =
        jobRepository.findByIdOrNull(jobId) ?: error("job을 찾을 수 없습니다: jobId=$jobId")
}
