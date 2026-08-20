package com.example.credit_system_kotlin.benchmark

import com.example.credit_system_kotlin.global.config.WorkerProperties
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.job.worker.GenerationJobProcessor
import com.example.credit_system_kotlin.job.worker.GenerationWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.AdditionalAnswers
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Pageable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max

@Tag("benchmark")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class WorkerBatchSizeBenchmark @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val realJobRepository: JobRepository
) {

    @Test
    fun `batch_size별 유휴율을 측정한다`() {
        val windowSeconds = java.lang.Long.getLong("bench.window-seconds", DEFAULT_WINDOW_SECONDS)
        val durations = parseLongCsv(System.getProperty("bench.durations"), DEFAULT_DURATIONS)
        val batchSizes = parseIntCsv(System.getProperty("bench.batch-sizes"), DEFAULT_BATCH_SIZES)
        val workerConcurrency = Integer.getInteger("bench.worker-concurrency", DEFAULT_WORKER_CONCURRENCY)
        val pollMillis = java.lang.Long.getLong("bench.poll-millis", DEFAULT_POLL_MILLIS)

        runCell(100, 20, workerConcurrency, 2, pollMillis)

        val sortedDurationsDesc = durations.sortedDescending()
        val sortedBatchSizesAsc = batchSizes.sorted()

        val results = ArrayList<BatchSizeResult>()
        for (duration in sortedDurationsDesc) {
            for (batchSize in sortedBatchSizesAsc) {
                results.add(runCell(duration, batchSize, workerConcurrency, windowSeconds, pollMillis))
            }
        }

        printMarkdownTable(results)
    }

    private fun runCell(
        durationMillis: Long,
        batchSize: Int,
        concurrency: Int,
        windowSeconds: Long,
        pollMillis: Long
    ): BatchSizeResult {
        jdbcTemplate.execute("TRUNCATE TABLE jobs")

        val backlogSize = max(
            20.0,
            ceil(windowSeconds * concurrency / (durationMillis / 1000.0) * 1.5)
        ).toInt()
        seedBacklog(backlogSize)

        val rowsRead = AtomicLong()
        val claimCount = AtomicLong()
        val rollbackCount = AtomicLong()
        val countingRepository = wrapWithCounters(rowsRead, claimCount, rollbackCount)

        val completedCount = AtomicInteger()
        val fakeProcessor = mock<GenerationJobProcessor>()
        doAnswer {
            Thread.sleep(durationMillis)
            completedCount.incrementAndGet()
            null
        }.whenever(fakeProcessor).runGeneration(any())

        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = concurrency
        executor.maxPoolSize = concurrency
        executor.queueCapacity = 0
        executor.setThreadNamePrefix("bench-worker-")
        executor.initialize()

        val worker = GenerationWorker(
            countingRepository, fakeProcessor, executor,
            WorkerProperties(true, batchSize, concurrency), pollMillis
        )

        val failure = AtomicReference<Throwable?>()
        val driver = Executors.newSingleThreadScheduledExecutor()
        driver.scheduleWithFixedDelay({
            try {
                worker.dispatchPendingJobs()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }, 0, pollMillis, TimeUnit.MILLISECONDS)

        val wallStart = System.nanoTime()
        try {
            Thread.sleep(windowSeconds * 1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        driver.shutdown()
        try {
            driver.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val wallEnd = System.nanoTime()
        val completed = completedCount.get()

        executor.shutdown()
        try {
            executor.threadPoolExecutor.awaitTermination(
                max(5L, durationMillis / 1000 + 5), TimeUnit.SECONDS
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        failure.get()?.let {
            throw IllegalStateException(
                "드라이버가 dispatchPendingJobs에서 예외를 던졌다 " +
                    "(durationMillis=$durationMillis, batchSize=$batchSize)",
                it
            )
        }

        assertThat(completed)
            .`as`("완료 건수가 0이면 안 된다 (durationMillis=%d, batchSize=%d)", durationMillis, batchSize)
            .isGreaterThan(0)

        val actualWindowSeconds = (wallEnd - wallStart) / 1_000_000_000.0
        val theoreticalTps = concurrency / (durationMillis / 1000.0)
        val observedTps = completed / actualWindowSeconds
        val idlePercent = max(0.0, 1.0 - observedTps / theoreticalTps) * 100.0
        val rowsReadPerCompletion = rowsRead.get() / completed.toDouble()
        val wastedUpdatesPerCompletion = rollbackCount.get() * 2 / completed.toDouble()

        return BatchSizeResult(
            durationMillis, batchSize, concurrency, theoreticalTps, observedTps,
            idlePercent, rowsReadPerCompletion, wastedUpdatesPerCompletion
        )
    }

    private fun seedBacklog(count: Int) {
        val now = Instant.now()
        // Java 원본은 result_url 자리에 null 을 넘겼지만, Kotlin에서 batchUpdate 는
        // List<Array<Any>> (원소 non-null)를 요구한다. result_url 은 nullable 컬럼이라
        // INSERT 목록에서 빼면 그대로 NULL 이 들어가므로 결과가 같고 타입도 깨끗하다.
        val rows = (0 until count).map {
            arrayOf<Any>(1L, "HOLDING", 0, 100L, "bench-prompt", now, now)
        }
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO jobs (organization_id, status, attempt_no, hold_amount, prompt, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            rows
        )
    }

    private fun wrapWithCounters(
        rowsRead: AtomicLong,
        claimCount: AtomicLong,
        rollbackCount: AtomicLong
    ): JobRepository {
        val countingRepository = mock<JobRepository>(
            defaultAnswer = AdditionalAnswers.delegatesTo<Any>(realJobRepository)
        )

        doAnswer { invocation ->
            val status = invocation.getArgument<JobStatus>(0)
            val pageable = invocation.getArgument<Pageable>(1)
            val jobs: List<Job> = realJobRepository.findByStatusOrderByIdAsc(status, pageable)
            rowsRead.addAndGet(jobs.size.toLong())
            jobs
        }.whenever(countingRepository).findByStatusOrderByIdAsc(eq(JobStatus.HOLDING), any())

        doAnswer { invocation ->
            val jobId = invocation.getArgument<Long>(0)
            val attemptNo = invocation.getArgument<Int>(1)
            val now = invocation.getArgument<Instant>(2)
            claimCount.incrementAndGet()
            realJobRepository.startProcessingIfAttemptMatches(jobId, attemptNo, now)
        }.whenever(countingRepository).startProcessingIfAttemptMatches(any(), any(), any())

        doAnswer { invocation ->
            val jobId = invocation.getArgument<Long>(0)
            val newStatus = invocation.getArgument<JobStatus>(1)
            val expectedStatus = invocation.getArgument<JobStatus>(2)
            val attemptNo = invocation.getArgument<Int>(3)
            val now = invocation.getArgument<Instant>(4)
            if (newStatus == JobStatus.HOLDING) {
                rollbackCount.incrementAndGet()
            }
            realJobRepository.transitionIfStatusAndAttemptMatch(
                jobId, newStatus, expectedStatus, attemptNo, now
            )
        }.whenever(countingRepository).transitionIfStatusAndAttemptMatch(
            any(), any(), any(), any(), any()
        )

        return countingRepository
    }

    companion object {
        private const val DEFAULT_WINDOW_SECONDS = 10L
        private const val DEFAULT_DURATIONS = "5000,1000,500,200,100,50"
        private const val DEFAULT_BATCH_SIZES = "1,2,4,8,20"
        private const val DEFAULT_WORKER_CONCURRENCY = 3
        private const val DEFAULT_POLL_MILLIS = 500L

        @Container
        @JvmStatic
        val mysql: MySQLContainer = MySQLContainer("mysql:8.4")
            .withDatabaseName("credit_system")
            .withUsername("credit")
            .withPassword("credit")
            .withCommand("--max-connections=200")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.datasource.driver-class-name") { mysql.driverClassName }
        }

        private fun parseLongCsv(property: String?, fallback: String): List<Long> {
            val value = if (property.isNullOrBlank()) fallback else property
            return value.split(",").map { it.trim().toLong() }
        }

        private fun parseIntCsv(property: String?, fallback: String): List<Int> {
            val value = if (property.isNullOrBlank()) fallback else property
            return value.split(",").map { it.trim().toInt() }
        }

        private fun printMarkdownTable(results: List<BatchSizeResult>) {
            val sb = StringBuilder()
            sb.append("\n")
            sb.append("| job 소요시간 | batch-size | 이론 TPS | 실측 TPS | 유휴율 % | 완료 1건당 읽은 행 | 완료 1건당 헛돈 UPDATE |\n")
            sb.append("|---|---|---|---|---|---|---|\n")
            for (r in results) {
                sb.append(
                    "| %d | %d | %.1f | %.1f | %.1f | %.1f | %.1f |%n".format(
                        r.durationMillis, r.batchSize, r.theoreticalTps, r.observedTps,
                        r.idlePercent, r.rowsReadPerCompletion, r.wastedUpdatesPerCompletion
                    )
                )
            }
            println(sb)
        }
    }
}
