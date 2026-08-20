package com.example.credit_system_kotlin.benchmark

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

object BenchmarkHarness {

    private const val READY_TIMEOUT_SECONDS = 30L
    private const val DONE_TIMEOUT_MINUTES = 5L

    fun run(
        strategy: DeductStrategy,
        concurrency: Int,
        totalRequests: Int,
        accountId: Long,
        amount: Long
    ): BenchmarkResult {
        val executor = Executors.newFixedThreadPool(concurrency)
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)
        val done = CountDownLatch(concurrency)

        val successCount = AtomicInteger()
        val failureCount = AtomicInteger()
        val totalRetries = AtomicLong()
        val recordedCount = AtomicInteger()
        val latenciesMicros = LongArray(totalRequests)

        val baseShare = totalRequests / concurrency
        val remainder = totalRequests % concurrency

        val workers = ArrayList<Future<*>>(concurrency)

        var offset = 0
        for (t in 0 until concurrency) {
            val myShare = baseShare + if (t < remainder) 1 else 0
            val myOffset = offset
            offset += myShare

            workers.add(
                executor.submit(
                    Runnable {
                        ready.countDown()
                        try {
                            start.await()
                            for (i in 0 until myShare) {
                                val begin = System.nanoTime()
                                val outcome = strategy.deduct(accountId, amount)
                                val end = System.nanoTime()
                                latenciesMicros[myOffset + i] = (end - begin) / 1000L
                                if (outcome.success) {
                                    successCount.incrementAndGet()
                                } else {
                                    failureCount.incrementAndGet()
                                }
                                totalRetries.addAndGet(outcome.retries.toLong())
                                recordedCount.incrementAndGet()
                            }
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("Benchmark worker interrupted", e)
                        } finally {
                            done.countDown()
                        }
                    }
                )
            )
        }

        try {
            val allReady = ready.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            check(allReady) {
                "Benchmark threads failed to reach the start barrier within ${READY_TIMEOUT_SECONDS}s " +
                    "(strategy=${strategy.name}, concurrency=$concurrency)"
            }

            val wallStart = System.nanoTime()
            start.countDown()
            val finished = done.await(DONE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val wallEnd = System.nanoTime()
            executor.shutdown()

            check(finished) {
                "Benchmark run timed out after $DONE_TIMEOUT_MINUTES minutes " +
                    "(strategy=${strategy.name}, concurrency=$concurrency)"
            }

            var workerFailure: IllegalStateException? = null
            for (worker in workers) {
                try {
                    worker.get()
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    if (workerFailure == null) {
                        workerFailure = IllegalStateException(
                            "Benchmark worker failed (strategy=${strategy.name}, concurrency=$concurrency)",
                            cause
                        )
                    } else {
                        workerFailure.addSuppressed(cause)
                    }
                }
            }
            if (workerFailure != null) {
                throw workerFailure
            }

            val recorded = recordedCount.get()
            check(recorded == totalRequests) {
                "Benchmark recorded $recorded of $totalRequests operations; " +
                    "latency percentiles would be skewed by unexecuted samples " +
                    "(strategy=${strategy.name}, concurrency=$concurrency)"
            }

            val elapsedSeconds = (wallEnd - wallStart) / 1_000_000_000.0
            val tps = totalRequests / elapsedSeconds

            val sorted = latenciesMicros.copyOf()
            sorted.sort()

            return BenchmarkResult(
                strategy = strategy.name,
                concurrency = concurrency,
                tps = tps,
                p50Micros = percentile(sorted, 0.50),
                p95Micros = percentile(sorted, 0.95),
                p99Micros = percentile(sorted, 0.99),
                successCount = successCount.get(),
                failureCount = failureCount.get(),
                totalRetries = totalRetries.get()
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Benchmark interrupted", e)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun percentile(sorted: LongArray, p: Double): Long {
        if (sorted.isEmpty()) {
            return 0L
        }
        val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}
