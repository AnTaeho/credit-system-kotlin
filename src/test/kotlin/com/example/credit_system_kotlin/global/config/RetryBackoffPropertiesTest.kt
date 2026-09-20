package com.example.credit_system_kotlin.global.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * backoff 설정이 말이 되는 값인지 기동에서 막는다. base 가 0 이면 backoff 가 없는 것과 같고,
 * multiplier 가 0 이면 두 번째 재시도부터 대기가 사라진다 — 둘 다 "설정은 있는데 효과는 없는"
 * 상태라서, 조용히 통과시키면 외부가 죽었을 때 상한을 몇 초 만에 태운다.
 */
class RetryBackoffPropertiesTest {

    @Test
    fun `생략하면 기본값은 10초, 4배, 상한 300초다`() {
        val backoff = AppProperties.RetryBackoff()

        assertThat(backoff.baseSeconds).isEqualTo(10L)
        assertThat(backoff.multiplier).isEqualTo(4L)
        assertThat(backoff.maxSeconds).isEqualTo(300L)
    }

    @Test
    fun `간격은 base에서 multiplier배씩 늘어난다`() {
        val backoff = AppProperties.RetryBackoff()

        assertThat(backoff.delayFor(1)).isEqualTo(Duration.ofSeconds(10))
        assertThat(backoff.delayFor(2)).isEqualTo(Duration.ofSeconds(40))
        assertThat(backoff.delayFor(3)).isEqualTo(Duration.ofSeconds(160))
    }

    @Test
    fun `간격은 max-seconds에서 잘린다`() {
        val backoff = AppProperties.RetryBackoff()

        assertThat(backoff.delayFor(4)).isEqualTo(Duration.ofSeconds(300))
        assertThat(backoff.delayFor(5)).isEqualTo(Duration.ofSeconds(300))
    }

    /** 상한에 닿으면 곱셈을 멈추므로 시도 횟수가 아무리 커도 Long 이 넘치지 않는다. */
    @Test
    fun `시도 횟수가 커져도 상한을 넘지 않는다`() {
        val backoff = AppProperties.RetryBackoff(baseSeconds = 10, multiplier = 1000, maxSeconds = 300)

        assertThat(backoff.delayFor(100)).isEqualTo(Duration.ofSeconds(300))
    }

    @Test
    fun `multiplier가 1이면 간격이 일정하다`() {
        val backoff = AppProperties.RetryBackoff(baseSeconds = 1, multiplier = 1, maxSeconds = 1)

        assertThat(backoff.delayFor(1)).isEqualTo(Duration.ofSeconds(1))
        assertThat(backoff.delayFor(3)).isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `base-seconds가 0이면 거부한다`() {
        assertThatThrownBy { AppProperties.RetryBackoff(baseSeconds = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("base-seconds")
    }

    @Test
    fun `multiplier가 0이면 거부한다`() {
        assertThatThrownBy { AppProperties.RetryBackoff(multiplier = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("multiplier")
    }

    @Test
    fun `max-seconds가 base보다 작으면 거부한다`() {
        assertThatThrownBy { AppProperties.RetryBackoff(baseSeconds = 10, maxSeconds = 9) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max-seconds")
    }

    @Test
    fun `max-seconds가 base와 같으면 허용한다`() {
        assertThatCode { AppProperties.RetryBackoff(baseSeconds = 10, maxSeconds = 10) }
            .doesNotThrowAnyException()
    }
}
