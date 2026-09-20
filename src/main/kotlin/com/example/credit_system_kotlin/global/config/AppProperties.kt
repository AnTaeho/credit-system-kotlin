package com.example.credit_system_kotlin.global.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val generation: Generation,
    val stub: Stub,
    val processing: Processing,
    val idempotency: Idempotency,
    val admin: Admin = Admin()
) {

    /**
     * [timeoutSeconds] 는 외부 생성 한 번을 기다려 주는 상한이다. 이 상한을 지키는 주체는
     * 호출자가 아니라 생성 클라이언트 구현이다. 스텁은 스스로 이 값에서 끊고, 진짜 Claude
     * 구현에서는 같은 값이 SDK 클라이언트의 타임아웃 설정으로 넘어간다.
     */
    data class Generation(
        val cost: Long,
        val maxAttempts: Int,
        val timeoutSeconds: Long = DEFAULT_GENERATION_TIMEOUT_SECONDS,
        val retryBackoff: RetryBackoff = RetryBackoff()
    ) {
        init {
            require(timeoutSeconds >= 1) { "generation timeout-seconds는 1 이상이어야 합니다." }
        }

        fun timeoutMillis(): Long = timeoutSeconds * MILLIS_PER_SECOND
    }

    /**
     * 재시도 사이에 두는 대기. 없으면 재시도는 다음 디스패치 주기(0.5초)에 바로 잡혀서,
     * 외부가 죽어 있을 때 상한 3회를 몇 초 만에 태우고 외부 호출 비용만 3번 나간다.
     *
     * 간격은 `base * multiplier^(재시도 횟수 - 1)` 이고 [maxSeconds] 에서 자른다.
     * 잠깐 문제면 빨리 다시, 오래가면 덜 자주 두드린다는 뜻이다. 기본값(10초/4배)이면
     * 실제로 쓰이는 값은 `max-attempts = 3` 이라 10초와 40초 두 번뿐이다.
     */
    data class RetryBackoff(
        val baseSeconds: Long = DEFAULT_RETRY_BASE_SECONDS,
        val multiplier: Long = DEFAULT_RETRY_MULTIPLIER,
        val maxSeconds: Long = DEFAULT_RETRY_MAX_SECONDS
    ) {
        init {
            require(baseSeconds >= 1) { "generation retry-backoff base-seconds는 1 이상이어야 합니다." }
            require(multiplier >= 1) { "generation retry-backoff multiplier는 1 이상이어야 합니다." }
            require(maxSeconds >= baseSeconds) {
                "generation retry-backoff max-seconds는 base-seconds 이상이어야 합니다."
            }
        }

        /**
         * [retryCount] 번째 재시도(1부터) 앞에 둘 대기. 곱셈을 한 번씩 하면서 상한에 닿으면
         * 즉시 끊는다 — 시도 횟수가 커져도 `Long` 이 넘치지 않게 하려는 것이다.
         */
        fun delayFor(retryCount: Int): Duration {
            require(retryCount >= 1) { "retryCount는 1 이상이어야 합니다." }
            var seconds = baseSeconds
            repeat(retryCount - 1) {
                if (seconds > maxSeconds / multiplier) {
                    return Duration.ofSeconds(maxSeconds)
                }
                seconds *= multiplier
            }
            return Duration.ofSeconds(minOf(seconds, maxSeconds))
        }
    }

    /** [hang] 은 타임아웃조차 먹지 않는 "응답 없음"을 재현하는 장애 주입 손잡이다. 기본은 꺼짐. */
    data class Stub(
        val failureRate: Double,
        val minDelayMillis: Long,
        val maxDelayMillis: Long,
        val hang: Boolean = false
    )

    /**
     * PROCESSING 정체 회수의 두 상한.
     *
     * [timeoutSeconds] 는 **후보를 고르는** 기준이다. 이보다 오래 PROCESSING 인 job 이
     * 정체 스캔에 걸리고, 거기서 heartbeat 가 LIVE 면 "워커가 살아 있다"로 보고 건너뛴다.
     *
     * [absoluteTimeoutSeconds] 는 그 건너뜀에 두는 **절대 상한**이다. 워커 스레드가 멈추면
     * (hang) 종지기 스레드는 워커 상태를 보지 않고 계속 갱신하므로 heartbeat 는 영원히
     * LIVE 다. 두 그물 모두 놓치고 돈이 held 에 영구히 묶인다. 이 상한은 "아무리 살아
     * 있다고 우겨도 이만큼 지났으면 내린다"는 마지막 그물이다.
     *
     * 반드시 [timeoutSeconds] **보다 커야 한다.** 같거나 작으면 절대 상한이 후보 선정
     * 기준을 덮어써서, heartbeat 가 살아 있는 정상 job 을 후보가 되는 즉시 전부 회수한다 —
     * 백스톱이 아니라 그냥 짧은 타임아웃이 되어 버린다.
     */
    data class Processing(
        val timeoutSeconds: Long,
        val absoluteTimeoutSeconds: Long = DEFAULT_ABSOLUTE_TIMEOUT_SECONDS
    ) {
        init {
            require(absoluteTimeoutSeconds > timeoutSeconds) {
                "processing absolute-timeout-seconds는 timeout-seconds보다 커야 합니다."
            }
        }
    }

    data class Idempotency(val retentionDays: Long) {
        init {
            require(retentionDays >= 1) { "idempotency retention-days는 1 이상이어야 합니다." }
        }
    }

    /** 운영자 지급 1회 상한. 결제 없이 돈을 만드는 경로라 한 번에 만들 수 있는 양을 묶어 둔다. */
    data class Admin(val maxGrantAmount: Long = DEFAULT_MAX_GRANT_AMOUNT) {
        init {
            require(maxGrantAmount >= 1) { "admin max-grant-amount는 1 이상이어야 합니다." }
        }
    }

    companion object {
        const val DEFAULT_MAX_GRANT_AMOUNT = 1_000_000L
        const val DEFAULT_GENERATION_TIMEOUT_SECONDS = 20L
        const val DEFAULT_ABSOLUTE_TIMEOUT_SECONDS = 300L
        const val DEFAULT_RETRY_BASE_SECONDS = 10L
        const val DEFAULT_RETRY_MULTIPLIER = 4L
        const val DEFAULT_RETRY_MAX_SECONDS = 300L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
