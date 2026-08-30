package com.example.credit_system_kotlin.job.dto

import com.example.credit_system_kotlin.job.domain.Job
import java.time.Instant

data class JobResponse(
    val id: Long,
    val status: String,
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
                job.holdAmount,
                job.prompt,
                job.resultUrl,
                job.updatedAt
            )
    }
}
