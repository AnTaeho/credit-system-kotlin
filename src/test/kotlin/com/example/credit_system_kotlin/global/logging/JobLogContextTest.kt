package com.example.credit_system_kotlin.global.logging

import ch.qos.logback.classic.Logger
import com.example.credit_system_kotlin.heartbeat.HeartbeatRegistry
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.generation.GenerationClient
import com.example.credit_system_kotlin.job.generation.GenerationTimeoutException
import com.example.credit_system_kotlin.job.service.JobLifecycleService
import com.example.credit_system_kotlin.job.worker.GenerationJobProcessor
import com.example.credit_system_kotlin.support.MdcSnapshotAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * job 식별자가 로그에 들어가는지, 그리고 **재사용된 스레드에 남지 않는지**.
 *
 * 뒤쪽이 이 조각의 진짜 위험이다. 워커와 스케줄러는 스레드 풀 위에서 돌고, 풀은 스레드를 다시 쓴다.
 * job A 의 MDC 를 지우지 않으면 같은 스레드가 집은 job B 의 로그가 A 의 것으로 보인다. 식별자가
 * 없는 것보다 나쁘다 — 없으면 못 묶을 뿐이지만, 남으면 틀리게 묶는다.
 */
class JobLogContextTest {

    private val heartbeatRegistry: HeartbeatRegistry = mock()
    private val generationClient: GenerationClient = mock()
    private val jobLifecycleService: JobLifecycleService = mock()
    private val heartbeatFuture: ScheduledFuture<*> = mock<ScheduledFuture<Any>>()

    private lateinit var processor: GenerationJobProcessor
    private lateinit var processorLogger: Logger
    private lateinit var appender: MdcSnapshotAppender

    @BeforeEach
    fun setUp() {
        processor = GenerationJobProcessor(heartbeatRegistry, generationClient, jobLifecycleService)
        appender = MdcSnapshotAppender()
        appender.start()
        processorLogger = LoggerFactory.getLogger(GenerationJobProcessor::class.java) as Logger
        processorLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        processorLogger.detachAppender(appender)
        MDC.clear()
    }

    @Test
    fun `워커 로그에 jobId 와 attemptNo 가 MDC 로 들어간다`() {
        processor.runGeneration(timingOutJob(id = 7L, attemptNo = 2))

        val mdc = appender.list.single().mdcPropertyMap
        assertThat(mdc[LogContext.JOB_ID]).isEqualTo("7")
        assertThat(mdc[LogContext.ATTEMPT_NO]).isEqualTo("2")
        // 요청 ID 는 여기 없다. 워커는 HTTP 밖이고, 접수 로그 한 줄이 두 세계를 잇는다.
        assertThat(mdc).doesNotContainKey(LogContext.REQUEST_ID)
    }

    @Test
    fun `같은 스레드가 두 job 을 연달아 처리해도 앞의 jobId 가 따라붙지 않는다`() {
        val singleThread = Executors.newSingleThreadExecutor()
        try {
            singleThread.submit { processor.runGeneration(timingOutJob(id = 11L, attemptNo = 0)) }
                .get(5, TimeUnit.SECONDS)
            singleThread.submit { processor.runGeneration(timingOutJob(id = 22L, attemptNo = 1)) }
                .get(5, TimeUnit.SECONDS)

            val mdcs = appender.list.map { it.mdcPropertyMap }
            assertThat(mdcs).hasSize(2)
            assertThat(mdcs[0][LogContext.JOB_ID]).isEqualTo("11")
            assertThat(mdcs[1][LogContext.JOB_ID]).isEqualTo("22")
            assertThat(mdcs[1][LogContext.ATTEMPT_NO]).isEqualTo("1")

            // 같은 스레드에 아무것도 남지 않았는지 직접 본다. 로그 단언만으로는
            // "두 번째 job 이 자기 값으로 덮어썼을 뿐"인 경우를 가려내지 못한다.
            val leftOver = singleThread.submit<Map<String, String>?> { MDC.getCopyOfContextMap() }
                .get(5, TimeUnit.SECONDS)
            assertThat(leftOver).satisfiesAnyOf(
                { assertThat(it).isNull() },
                { assertThat(it).isEmpty() }
            )
        } finally {
            singleThread.shutdownNow()
        }
    }

    @Test
    fun `블록을 빠져나오면 이전 값이 돌아온다`() {
        MDC.put(LogContext.JOB_ID, "바깥")

        withJobLogContext(99L, 3) {
            assertThat(MDC.get(LogContext.JOB_ID)).isEqualTo("99")
            assertThat(MDC.get(LogContext.ATTEMPT_NO)).isEqualTo("3")
        }

        assertThat(MDC.get(LogContext.JOB_ID)).isEqualTo("바깥")
        assertThat(MDC.get(LogContext.ATTEMPT_NO)).isNull()
    }

    @Test
    fun `블록이 예외를 던져도 MDC 를 되돌린다`() {
        runCatching {
            withJobLogContext(1L, 0) { throw IllegalStateException("폭발") }
        }

        assertThat(MDC.get(LogContext.JOB_ID)).isNull()
        assertThat(MDC.get(LogContext.ATTEMPT_NO)).isNull()
    }

    /** 타임아웃 경로를 쓰는 이유는 하나다 — 로그를 한 줄 남기는 가장 짧은 길이다. */
    private fun timingOutJob(id: Long, attemptNo: Int): Job {
        val job = Job.hold(1L, 100L, "prompt-$id")
        ReflectionTestUtils.setField(job, "id", id)
        ReflectionTestUtils.setField(job, "attemptNo", attemptNo)
        doReturn(heartbeatFuture).whenever(heartbeatRegistry).startHeartbeat(id, attemptNo)
        whenever(generationClient.generate("prompt-$id"))
            .thenThrow(GenerationTimeoutException("prompt-$id", 20_000L))
        return job
    }
}
