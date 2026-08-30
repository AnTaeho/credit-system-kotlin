package com.example.credit_system_kotlin.job.worker

import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.stub.GenerationStubClient
import com.example.credit_system_kotlin.job.stub.StubGenerationException
import org.springframework.stereotype.Component

@Component
class GenerationJobProcessor(
    private val stubClient: GenerationStubClient,
    private val jobLifecycleService: JobLifecycleService
) {

    fun runGeneration(job: Job) {
        val resultUrl = generateOrMarkFailed(job) ?: return
        jobLifecycleService.confirm(job.persistedId, resultUrl)
    }

    /** 생성에 성공하면 resultUrl, 실패하면 FAILED로 기록하고 null */
    private fun generateOrMarkFailed(job: Job): String? =
        try {
            stubClient.generate(job.prompt)
        } catch (_: StubGenerationException) {
            jobLifecycleService.markFailed(job.persistedId)
            null
        }
}
