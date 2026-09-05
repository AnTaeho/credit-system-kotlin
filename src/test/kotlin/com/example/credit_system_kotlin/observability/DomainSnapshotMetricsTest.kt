package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DomainSnapshotTaken
import com.example.credit_system_kotlin.support.FixedMutableClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class DomainSnapshotMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val fixedInstant = Instant.parse("2026-09-05T00:00:00Z")
    private val clock = FixedMutableClock(fixedInstant)

    private lateinit var metrics: DomainSnapshotMetrics

    @BeforeEach
    fun setUp() {
        metrics = DomainSnapshotMetrics(registry, clock)
    }

    @Test
    fun `이벤트를 받으면 게이지 6개가 그 값이 된다`() {
        metrics.onSnapshotTaken(snapshot())

        assertThat(gauge(DomainSnapshotMetrics.OUTSTANDING_COUNT_METRIC)).isEqualTo(3.0)
        assertThat(gauge(DomainSnapshotMetrics.OUTSTANDING_AMOUNT_METRIC)).isEqualTo(300.0)
        assertThat(gauge(DomainSnapshotMetrics.OLDEST_PENDING_AGE_METRIC)).isEqualTo(90.0)
        assertThat(gauge(DomainSnapshotMetrics.NEGATIVE_BALANCE_ORGS_METRIC)).isEqualTo(1.0)
        assertThat(gauge(DomainSnapshotMetrics.JOBS_WITHOUT_HOLD_METRIC)).isEqualTo(2.0)
        assertThat(gauge(DomainSnapshotMetrics.UNSETTLED_TERMINAL_METRIC)).isEqualTo(4.0)
    }

    @Test
    fun `이벤트를 두 번 받으면 게이지는 마지막 값으로 덮이고 cycles는 2가 된다`() {
        metrics.onSnapshotTaken(snapshot())
        metrics.onSnapshotTaken(
            snapshot(
                outstandingHoldCount = 0,
                outstandingHoldAmount = 0,
                oldestPendingAgeSeconds = 0,
                negativeBalanceOrgs = 0,
                jobsWithoutHold = 0,
                unsettledTerminalJobs = 0
            )
        )

        assertThat(gauge(DomainSnapshotMetrics.OUTSTANDING_COUNT_METRIC)).isZero()
        assertThat(gauge(DomainSnapshotMetrics.OUTSTANDING_AMOUNT_METRIC)).isZero()
        assertThat(gauge(DomainSnapshotMetrics.OLDEST_PENDING_AGE_METRIC)).isZero()
        assertThat(gauge(DomainSnapshotMetrics.NEGATIVE_BALANCE_ORGS_METRIC)).isZero()
        assertThat(gauge(DomainSnapshotMetrics.JOBS_WITHOUT_HOLD_METRIC)).isZero()
        assertThat(gauge(DomainSnapshotMetrics.UNSETTLED_TERMINAL_METRIC)).isZero()
        assertThat(registry.get(DomainSnapshotMetrics.CYCLES_METRIC).counter().count()).isEqualTo(2.0)
    }

    @Test
    fun `duration 타이머가 이벤트의 duration을 기록한다`() {
        metrics.onSnapshotTaken(snapshot(duration = Duration.ofMillis(250)))

        val timer = registry.get(DomainSnapshotMetrics.DURATION_METRIC).timer()
        assertThat(timer.count()).isEqualTo(1)
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0)
    }

    @Test
    fun `이벤트 전 staleness는 -1이고 이벤트 후 시계를 앞으로 당기면 흐른 초가 나온다`() {
        assertThat(gauge(DomainSnapshotMetrics.STALENESS_METRIC)).isEqualTo(-1.0)

        metrics.onSnapshotTaken(snapshot())
        clock.advance(Duration.ofSeconds(30))

        assertThat(gauge(DomainSnapshotMetrics.STALENESS_METRIC)).isEqualTo(30.0)
    }

    private fun gauge(name: String): Double = registry.get(name).gauge().value()

    private fun snapshot(
        outstandingHoldCount: Long = 3,
        outstandingHoldAmount: Long = 300,
        oldestPendingAgeSeconds: Long = 90,
        negativeBalanceOrgs: Long = 1,
        jobsWithoutHold: Long = 2,
        unsettledTerminalJobs: Long = 4,
        duration: Duration = Duration.ofMillis(120)
    ) = DomainSnapshotTaken(
        outstandingHoldCount = outstandingHoldCount,
        outstandingHoldAmount = outstandingHoldAmount,
        oldestPendingAgeSeconds = oldestPendingAgeSeconds,
        negativeBalanceOrgs = negativeBalanceOrgs,
        jobsWithoutHold = jobsWithoutHold,
        unsettledTerminalJobs = unsettledTerminalJobs,
        duration = duration,
        takenAt = fixedInstant
    )
}
