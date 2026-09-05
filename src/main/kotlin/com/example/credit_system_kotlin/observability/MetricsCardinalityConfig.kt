package com.example.credit_system_kotlin.observability

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.config.MeterFilterReply
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(MetricsCardinalityConfig::class.java)

/**
 * 레지스트리 수준의 카디널리티 가드.
 *
 * 2단계의 enum([DefenseMetrics.VALID_COMBINATIONS])은 코드 수준 가드다 — 태그 값 집합이
 * 컴파일 타임에 닫혀 있어서 자유 문자열이 들어올 자리가 없다. 하지만 그 가드는 오늘 존재하는
 * 코드에만 걸린다. 앞으로 누가 `organizationId` 를 태그로 붙이는 계측을 새로 짜면 enum 은
 * 아무 말도 하지 않는다.
 *
 * 여기가 그 아래 계층이다. 2단계 문서의 "1차 멱등 조회 + DB 유니크 제약" 이중 방어와 같은
 * 구조로, 코드가 빠르게 걸러내고 최종 판정은 레지스트리가 한다.
 *
 * 거부된 미터에 대해 Micrometer 는 noop 미터를 돌려주므로 호출 코드는 죽지 않는다.
 * 관측 코드가 도메인 동작을 망가뜨려서는 안 된다는 2단계 원칙 그대로다.
 */
@Configuration
class MetricsCardinalityConfig {

    /**
     * 식별자 태그가 붙은 미터를 통째로 거부한다.
     *
     * 조직·job·멱등키는 값의 개수가 트래픽에 비례해 늘어나므로 시계열도 같이 폭발한다.
     * "어느 조직인가"는 로그의 질문이고, 지표는 전역 집계만 답한다.
     */
    @Bean
    fun denyIdentifierTagsMeterFilter(): MeterFilter = object : MeterFilter {

        private val warnedOnce = ConcurrentHashMap.newKeySet<String>()

        override fun accept(id: Meter.Id): MeterFilterReply {
            val offendingKey = FORBIDDEN_TAG_KEYS.firstOrNull { id.getTag(it) != null }
                ?: return MeterFilterReply.NEUTRAL
            if (warnedOnce.add("${id.name}/$offendingKey")) {
                log.warn(
                    "식별자 태그가 붙은 미터를 거부했다: name={}, tag={}. " +
                        "개별 식별은 로그의 몫이다 — 지표에 붙이면 카디널리티가 폭발한다",
                    id.name, offendingKey
                )
            }
            return MeterFilterReply.DENY
        }
    }

    /**
     * `credit.` 접두 미터의 태그 값 개수 상한.
     *
     * 지금 실제 값은 point 7개, outcome 7개, detector 2개다. 상한 32는 enum 이 지금의 네 배로
     * 커져도 걸리지 않는 값이고, 그 이상이 관측된다면 enum 이 아니라 자유 문자열이 태그로
     * 들어갔다는 뜻이다 — 그 시점에 막는 것이 이 필터의 목적이다.
     */
    @Bean
    fun creditPointTagLimitMeterFilter(): MeterFilter =
        MeterFilter.maximumAllowableTags(CREDIT_PREFIX, "point", MAX_TAG_VALUES, MeterFilter.deny())

    @Bean
    fun creditOutcomeTagLimitMeterFilter(): MeterFilter =
        MeterFilter.maximumAllowableTags(CREDIT_PREFIX, "outcome", MAX_TAG_VALUES, MeterFilter.deny())

    @Bean
    fun creditDetectorTagLimitMeterFilter(): MeterFilter =
        MeterFilter.maximumAllowableTags(CREDIT_PREFIX, "detector", MAX_TAG_VALUES, MeterFilter.deny())

    /**
     * 레지스트리 전체 미터 수 상한.
     *
     * JVM·Tomcat·Hikari 기본 미터만으로 수백 개가 등록되므로 여유를 크게 두되, 무한 증식은
     * 막는다. 앞의 두 필터를 우회하는 이름 폭발(미터 이름 자체에 식별자를 박는 식)에 대한
     * 마지막 그물이다.
     */
    @Bean
    fun maximumMetricsMeterFilter(): MeterFilter = MeterFilter.maximumAllowableMetrics(MAX_METERS)

    companion object {
        /** 값의 개수가 트래픽에 비례해 늘어나는 태그 키. snake_case 표기까지 함께 막는다. */
        val FORBIDDEN_TAG_KEYS = listOf(
            "organizationId", "organization_id",
            "jobId", "job_id",
            "idemKey", "idem_key"
        )

        const val CREDIT_PREFIX = "credit"
        const val MAX_TAG_VALUES = 32
        const val MAX_METERS = 2000
    }
}
