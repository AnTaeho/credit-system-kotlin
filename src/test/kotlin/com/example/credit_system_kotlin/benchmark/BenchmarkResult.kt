package com.example.credit_system_kotlin.benchmark

data class BenchmarkResult(
    val strategy: String,
    val concurrency: Int,
    val tps: Double,
    val p50Micros: Long,
    val p95Micros: Long,
    val p99Micros: Long,
    val successCount: Int,
    val failureCount: Int,
    val totalRetries: Long
)
