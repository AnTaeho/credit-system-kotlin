package com.example.credit_system_kotlin.job.service

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class JobLifecycleServiceTest @Autowired constructor(
    private val jobRepository: JobRepository
) {

    private val jobLifecycleService = JobLifecycleService(jobRepository)

    @Test
    fun `startProcessing은 상태를 PROCESSING으로 바꾼다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))

        jobLifecycleService.startProcessing(job.persistedId)

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.PROCESSING)
    }

    @Test
    fun `confirm은 완료 처리된다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobLifecycleService.startProcessing(job.persistedId)

        jobLifecycleService.confirm(job.persistedId, "https://stub/x.png")

        val found = jobRepository.findById(job.persistedId).orElseThrow()
        assertThat(found.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(found.resultUrl).isEqualTo("https://stub/x.png")
    }

    @Test
    fun `markFailed는 FAILED로 전이한다`() {
        val job = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobLifecycleService.startProcessing(job.persistedId)

        jobLifecycleService.markFailed(job.persistedId)

        assertThat(jobRepository.findById(job.persistedId).orElseThrow().status)
            .isEqualTo(JobStatus.FAILED)
    }
}
