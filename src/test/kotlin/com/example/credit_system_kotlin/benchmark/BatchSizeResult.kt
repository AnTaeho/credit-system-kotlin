package com.example.credit_system_kotlin.benchmark

data class BatchSizeResult(
    val durationMillis: Long,
    val batchSize: Int,
    val concurrency: Int,
    val theoreticalTps: Double,
    val observedTps: Double,
    val idlePercent: Double,
    val rowsReadPerCompletion: Double,
    val wastedUpdatesPerCompletion: Double
)
