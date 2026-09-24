package com.example.credit_system_kotlin.global.bulkhead

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.observability.IntakeBulkheadMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import java.util.concurrent.Semaphore

/**
 * 접수 벌크헤드를 켜고 끄는 자리.
 *
 * `app.db.intake-permits` 가 0 이하면 이 설정 자체가 없다 — 필터도, 세마포어도, 미터도 만들어지지
 * 않는다. 껐을 때가 켜기 전과 **정확히 같아야** 켬/끔 비교로 효과를 가릴 수 있다. 그래서 등록을
 * 만들어 두고 비활성만 시키는 대신 조건으로 설정 클래스를 통째로 없앤다.
 *
 * 필터를 빈으로 직접 노출하지 않는 것도 같은 이유다. 서블릿 환경에서 [jakarta.servlet.Filter] 빈은
 * 부트가 모든 경로에 자동 등록하므로, 등록 빈과 함께 두면 체인에 두 번 — 한 번은 경로 제한 없이 — 들어간다.
 *
 * **체인의 어디인가.** 시큐리티([com.example.credit_system_kotlin.auth.config.SecurityConfig],
 * 기본 순서 -100)보다 앞이고 요청 식별자 필터([com.example.credit_system_kotlin.global.logging.RequestIdFilter],
 * [Ordered.HIGHEST_PRECEDENCE])보다 뒤다. 인증 자체도 DB 를 쓸 수 있고(개발 로그인의 사용자 생성),
 * 요청 한 건이 DB 를 만지는 구간 전체를 허가 안에 두는 편이 상한의 뜻과 맞다. 대신 미인증 요청도
 * 허가를 기다린 뒤에야 401 을 받는다 — 접수 경로는 어차피 인증을 요구하므로 감수한다.
 * 식별자 필터보다 뒤에 두는 것은 여기서 기다리다 찍히는 로그에도 요청 식별자가 붙게 하려는 것이다.
 */
@Configuration
@ConditionalOnExpression("\${app.db.intake-permits:0} > 0")
class IntakeBulkheadConfig {

    @Bean
    fun intakeBulkheadFilterRegistration(
        appProperties: AppProperties,
        meterRegistry: MeterRegistry
    ): FilterRegistrationBean<IntakeBulkheadFilter> {
        val total = appProperties.db.intakePermits
        val permits = Semaphore(total, true)
        val filter = IntakeBulkheadFilter(permits, IntakeBulkheadMetrics(meterRegistry, permits, total))
        return FilterRegistrationBean(filter).apply {
            addUrlPatterns(IntakeBulkheadFilter.INTAKE_URL_PATTERN)
            order = ORDER
        }
    }

    companion object {
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 10
    }
}
