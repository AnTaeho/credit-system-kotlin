package com.example.credit_system_kotlin.observability

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import com.example.credit_system_kotlin.job.event.JobRecovered
import com.example.credit_system_kotlin.job.event.RecoveryDetector
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(DefenseMetrics::class.java)

@Component
class DefenseMetrics(
    private val registry: MeterRegistry
) {

    private val defenseCounters = ConcurrentHashMap<Pair<DefensePoint, DefenseOutcome>, Counter>()
    private val recoveryCounters = ConcurrentHashMap<RecoveryDetector, Counter>()

    init {
        for ((point, outcomes) in VALID_COMBINATIONS) {
            for (outcome in outcomes) {
                defenseCounters[point to outcome] = registerDefenseCounter(point, outcome)
            }
        }
        for (detector in RecoveryDetector.entries) {
            recoveryCounters[detector] = registerRecoveryCounter(detector)
        }
    }

    @EventListener
    fun onDefenseTriggered(event: DefenseTriggered) {
        defenseCounters.computeIfAbsent(event.point to event.outcome) { (point, outcome) ->
            log.warn("사전 등록되지 않은 방어 조합: point={}, outcome={}", point, outcome)
            registerDefenseCounter(point, outcome)
        }.increment()
    }

    @EventListener
    fun onJobRecovered(event: JobRecovered) {
        log.debug(
            "죽은 job 회수: jobId={}, attemptNo={}, detector={}",
            event.jobId, event.attemptNo, event.detector
        )
        recoveryCounters.computeIfAbsent(event.detector) { registerRecoveryCounter(it) }.increment()
    }

    private fun registerDefenseCounter(point: DefensePoint, outcome: DefenseOutcome): Counter =
        Counter.builder(DEFENSE_METRIC)
            .description("방어 장치가 가동한 횟수. outcome=applied 는 통과, 나머지는 막힌 시도다")
            .tag("point", point.name.lowercase())
            .tag("outcome", outcome.name.lowercase())
            .register(registry)

    private fun registerRecoveryCounter(detector: RecoveryDetector): Counter =
        Counter.builder(RECOVERY_METRIC)
            .description(
                "죽은 job 을 FAILED 로 회수한 횟수. detector=backstop 이 0이 아니면 heartbeat 누수, " +
                    "backstop_blind 는 heartbeat 저장소 장애 중 updatedAt 만으로 회수한 것이다"
            )
            .tag("detector", detector.name.lowercase())
            .register(registry)

    companion object {
        const val DEFENSE_METRIC = "credit.defense"
        const val RECOVERY_METRIC = "credit.job.recovery"

        val VALID_COMBINATIONS: Map<DefensePoint, Set<DefenseOutcome>> = mapOf(
            DefensePoint.HOLD_BALANCE to setOf(DefenseOutcome.APPLIED, DefenseOutcome.REJECTED),
            DefensePoint.IDEM_KEY to setOf(DefenseOutcome.APP_HIT, DefenseOutcome.DB_UNIQUE, DefenseOutcome.MISMATCH),
            DefensePoint.WORKER_CLAIM to setOf(
                DefenseOutcome.APPLIED, DefenseOutcome.LOST, DefenseOutcome.ROLLED_BACK
            ),
            DefensePoint.CONFIRM to setOf(DefenseOutcome.APPLIED, DefenseOutcome.STALE),
            DefensePoint.MARK_FAILED to setOf(DefenseOutcome.APPLIED, DefenseOutcome.STALE),
            DefensePoint.RETRY_CLAIM to setOf(DefenseOutcome.APPLIED, DefenseOutcome.LOST),
            DefensePoint.FINAL_REFUND to setOf(DefenseOutcome.APPLIED, DefenseOutcome.RACED)
        )
    }
}
