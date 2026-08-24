package com.example.credit_system_kotlin.job.api

import com.example.credit_system_kotlin.job.domain.Job
import java.time.Instant

data class HoldResult(val jobId: Long, val duplicate: Boolean)

data class JobCreateRequest(val idemKey: String, val prompt: String)

data class JobResponse(
    val id: Long,
    val status: String,
    val attemptNo: Int,
    val holdAmount: Long,
    val prompt: String,
    val resultUrl: String?,
    val updatedAt: Instant
) {
    companion object {
        fun from(job: Job): JobResponse =
            JobResponse(
                job.persistedId,
                job.status.name,
                job.attemptNo,
                job.holdAmount,
                job.prompt,
                job.resultUrl,
                job.updatedAt
            )
    }
}
