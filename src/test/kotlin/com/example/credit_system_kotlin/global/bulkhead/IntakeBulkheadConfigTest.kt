package com.example.credit_system_kotlin.global.bulkhead

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.config.appProperties
import com.example.credit_system_kotlin.observability.IntakeBulkheadMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * **꺼짐이 기본이고, 꺼짐은 없는 것과 같아야 한다.** 켬/끔을 나란히 재서 효과를 가리는 것이
 * 이 변경의 목적이라, 끈 쪽에 필터든 미터든 하나라도 남아 있으면 비교가 성립하지 않는다.
 */
class IntakeBulkheadConfigTest {

    private fun runnerWith(permits: Int) = ApplicationContextRunner()
        .withUserConfiguration(IntakeBulkheadConfig::class.java)
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withBean(AppProperties::class.java, { appProperties(db = AppProperties.Db(permits)) })
        .withPropertyValues("app.db.intake-permits=$permits")

    @Test
    fun `기본값 0 이면 필터도 미터도 만들어지지 않는다`() {
        runnerWith(AppProperties.DEFAULT_INTAKE_PERMITS).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeanNamesForType(FilterRegistrationBean::class.java)).isEmpty()
            val meters = context.getBean(MeterRegistry::class.java).meters.map { it.id.name }
            assertThat(meters).doesNotContain(
                IntakeBulkheadMetrics.PERMITS_USED_METRIC,
                IntakeBulkheadMetrics.WAIT_METRIC
            )
        }
    }

    @Test
    fun `음수여도 꺼진 것으로 본다`() {
        runnerWith(-1).run { context ->
            assertThat(context.getBeanNamesForType(FilterRegistrationBean::class.java)).isEmpty()
        }
    }

    @Test
    fun `양수면 접수 경로에만 걸린 필터가 등록된다`() {
        runnerWith(PERMITS).run { context ->
            val registration = context.getBean(FilterRegistrationBean::class.java)

            assertThat(registration.filter).isInstanceOf(IntakeBulkheadFilter::class.java)
            assertThat(registration.urlPatterns).containsExactly(IntakeBulkheadFilter.INTAKE_URL_PATTERN)
            // 시큐리티(기본 -100)보다 앞, 요청 식별자 필터(HIGHEST_PRECEDENCE)보다 뒤.
            assertThat(registration.order).isEqualTo(IntakeBulkheadConfig.ORDER)
            assertThat(registration.order).isLessThan(SECURITY_FILTER_ORDER)

            val meters = context.getBean(MeterRegistry::class.java).meters.map { it.id.name }
            assertThat(meters).contains(
                IntakeBulkheadMetrics.PERMITS_USED_METRIC,
                IntakeBulkheadMetrics.WAIT_METRIC
            )
        }
    }

    @Test
    fun `환경변수 APP_DB_INTAKE_PERMITS 로도 켜진다`() {
        // A/B 측정은 환경변수로 켠다. 이름이 어긋나면 "켠 줄 알았는데 꺼져 있었다"가 되고
        // 비교 자체가 무의미해지므로, 스프링이 쓰는 그 프로퍼티 소스로 그대로 확인한다.
        ApplicationContextRunner()
            .withUserConfiguration(IntakeBulkheadConfig::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBean(AppProperties::class.java, { appProperties(db = AppProperties.Db(PERMITS)) })
            .withInitializer { context ->
                val env = mapOf<String, Any>("APP_DB_INTAKE_PERMITS" to "$PERMITS")
                context.environment.propertySources
                    .addFirst(SystemEnvironmentPropertySource("test-env", env))
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeanNamesForType(FilterRegistrationBean::class.java)).hasSize(1)
            }
    }

    companion object {
        private const val PERMITS = 8

        /** `SecurityProperties.DEFAULT_FILTER_ORDER`. */
        private const val SECURITY_FILTER_ORDER = -100
    }
}
