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

/**
 * 방어 장치의 가동 기록을 Micrometer 카운터로 승격한다.
 *
 * 도메인/서비스/스케줄러는 [DefenseTriggered] / [JobRecovered] 이벤트만 발행하고,
 * 세는 책임은 이 컴포넌트가 진다. 여기가 Micrometer 를 아는 유일한 곳이다.
 *
 * [EventListener] 를 쓰고 `@TransactionalEventListener(AFTER_COMMIT)` 는 쓰지 않는다.
 * "조건부 UPDATE 가 0행을 돌려줬다"는 사실은 그 트랜잭션이 커밋되든 롤백되든 참이기 때문이다.
 * AFTER_COMMIT 을 걸면 롤백된 트랜잭션의 이벤트를 조용히 버리는데, 유니크 위반처럼
 * 롤백되는 경우가 오히려 가장 세고 싶은 사건이다.
 */
@Component
class DefenseMetrics(
    private val registry: MeterRegistry
) {

    private val defenseCounters = ConcurrentHashMap<Pair<DefensePoint, DefenseOutcome>, Counter>()
    private val recoveryCounters = ConcurrentHashMap<RecoveryDetector, Counter>()

    init {
        // 한 번도 발생하지 않은 조합을 lazy 등록에 맡기면 스크레이프에 시계열 자체가 없다.
        // Prometheus 의 rate()/increase() 는 "없는 시계열"과 "0인 시계열"을 다르게 다루므로,
        // 알람 규칙과 대시보드가 처음부터 성립하도록 유효 조합 전부를 0으로 깔아 둔다.
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
            // 사전 등록 목록에 없는 조합이 들어왔다는 것은 코드가 바뀌었는데 이 목록이 안 따라온
            // 것이다. 예외를 던져 요청 흐름을 죽이는 것보다, 세면서 경고를 남기는 편이 낫다 —
            // 관측 코드가 도메인 동작을 망가뜨려서는 안 된다.
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

        /** 각 방어 지점이 실제로 낼 수 있는 결과만 담는다. 여기 없는 조합은 코드상 발생하지 않는다. */
        val VALID_COMBINATIONS: Map<DefensePoint, Set<DefenseOutcome>> = mapOf(
            DefensePoint.HOLD_BALANCE to setOf(DefenseOutcome.APPLIED, DefenseOutcome.REJECTED),
            DefensePoint.IDEM_KEY to setOf(DefenseOutcome.APP_HIT, DefenseOutcome.DB_UNIQUE),
            DefensePoint.WORKER_CLAIM to setOf(DefenseOutcome.APPLIED, DefenseOutcome.LOST),
            DefensePoint.CONFIRM to setOf(DefenseOutcome.APPLIED, DefenseOutcome.STALE),
            DefensePoint.MARK_FAILED to setOf(DefenseOutcome.APPLIED, DefenseOutcome.STALE),
            DefensePoint.RETRY_CLAIM to setOf(DefenseOutcome.APPLIED, DefenseOutcome.LOST),
            DefensePoint.FINAL_REFUND to setOf(DefenseOutcome.APPLIED, DefenseOutcome.RACED)
        )
    }
}
