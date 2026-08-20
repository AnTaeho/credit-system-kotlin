package com.example.credit_system_kotlin.scheduler

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

class RedisOutageGateTest {

    private lateinit var gate: RedisOutageGate
    private lateinit var clock: MutableClock
    private lateinit var gateLogger: Logger
    private lateinit var logAppender: ListAppender<ILoggingEvent>

    @BeforeEach
    fun setUp() {
        val properties = appProperties(
            heartbeat = AppProperties.Heartbeat(
                timeoutSeconds = 10, refreshIntervalSeconds = 1, suppressionAlertSeconds = 60
            )
        )
        clock = MutableClock(Instant.now())
        gate = RedisOutageGate(properties, clock)

        logAppender = ListAppender<ILoggingEvent>().apply { start() }
        gateLogger = LoggerFactory.getLogger(RedisOutageGate::class.java) as Logger
        gateLogger.addAppender(logAppender)
    }

    @AfterEach
    fun tearDown() {
        gateLogger.detachAppender(logAppender)
    }

    @Test
    fun `임계치 전에는 억제 경보를 내지 않는다`() {
        beginOutage()
        continueOutage(Duration.ofSeconds(55))

        assertThat(errorLogs()).isEmpty()
    }

    @Test
    fun `억제가 임계치를 넘기면 ERROR로 경보한다`() {
        beginOutage()
        continueOutage(Duration.ofSeconds(60))

        assertThat(errorLogs()).hasSize(1)
        assertThat(errorLogs()[0].formattedMessage)
            .contains("60초")
            .contains("회수가 그동안 계속 억제")
    }

    @Test
    fun `억제 경보는 임계치 주기로만 재발행된다`() {
        beginOutage()
        continueOutage(Duration.ofSeconds(60))
        assertThat(errorLogs()).hasSize(1)

        continueOutage(Duration.ofSeconds(55))
        assertThat(errorLogs()).hasSize(1)

        continueOutage(Duration.ofSeconds(5))
        assertThat(errorLogs()).hasSize(2)
    }

    @Test
    fun `유예가 풀리면 억제 상태가 리셋되고 회복을 남긴다`() {
        beginOutage()
        continueOutage(Duration.ofSeconds(60))
        assertThat(errorLogs()).hasSize(1)

        clock.advance(Duration.ofSeconds(11))
        assertThat(gate.isInRecoveryGrace()).isFalse()
        assertThat(infoLogs()).hasSize(1)
        assertThat(infoLogs()[0].formattedMessage).contains("회수를 재개")

        beginOutage()
        continueOutage(Duration.ofSeconds(55))
        assertThat(errorLogs()).hasSize(1)

        continueOutage(Duration.ofSeconds(5))
        assertThat(errorLogs()).hasSize(2)
    }

    private fun beginOutage() {
        gate.recordFailure()
    }

    private fun continueOutage(duration: Duration) {
        var elapsed = 0L
        while (elapsed < duration.seconds) {
            clock.advance(Duration.ofSeconds(5))
            gate.recordFailure()
            gate.isInRecoveryGrace()
            elapsed += 5
        }
    }

    private fun errorLogs(): List<ILoggingEvent> = logsAt(Level.ERROR)

    private fun infoLogs(): List<ILoggingEvent> = logsAt(Level.INFO)

    private fun logsAt(level: Level): List<ILoggingEvent> =
        logAppender.list.filter { it.level == level }
}
