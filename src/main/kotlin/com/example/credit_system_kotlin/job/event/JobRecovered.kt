package com.example.credit_system_kotlin.job.event

enum class RecoveryDetector {
    HEARTBEAT,
    BACKSTOP,
    BACKSTOP_BLIND
}

data class JobRecovered(
    val jobId: Long,
    val attemptNo: Int,
    val detector: RecoveryDetector
)
