package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AccessTokenServiceTest {

    private val clock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val properties = JwtProperties(secret = SECRET, accessTtl = Duration.ofMinutes(15))
    private val service = AccessTokenService(properties, clock)

    @Test
    fun `발급한 토큰을 검증하면 같은 userId 와 role 이 나온다`() {
        assertThat(service.verify(service.issue(42L, UserRole.USER)))
            .isEqualTo(AccessPrincipal(42L, UserRole.USER))
        assertThat(service.verify(service.issue(7L, UserRole.ADMIN)))
            .isEqualTo(AccessPrincipal(7L, UserRole.ADMIN))
    }

    @Test
    fun `만료 직전까지는 유효하고 만료 시각부터는 null 이다`() {
        val token = service.issue(42L, UserRole.USER)

        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertThat(service.verify(token)).isNotNull()

        clock.advance(Duration.ofSeconds(2))
        assertThat(service.verify(token)).isNull()
    }

    @Test
    fun `서명이나 내용을 바꾼 토큰은 null 이다`() {
        val (header, payload, signature) = service.issue(42L, UserRole.USER).split(".")
        val otherPayload = service.issue(43L, UserRole.ADMIN).split(".")[1]
        val flipped = signature.dropLast(2) + if (signature.endsWith("AA")) "BB" else "AA"

        assertThat(service.verify("$header.$payload.$flipped")).`as`("서명 변조").isNull()
        assertThat(service.verify("$header.$otherPayload.$signature")).`as`("내용 바꿔치기").isNull()
    }

    @Test
    fun `다른 키로 서명한 토큰은 null 이다`() {
        val other = AccessTokenService(properties.copy(secret = "another-secret-0123456789-abcdefghij"), clock)

        assertThat(service.verify(other.issue(42L, UserRole.USER))).isNull()
    }

    @Test
    fun `issuer 가 다른 토큰은 null 이다`() {
        val other = AccessTokenService(properties.copy(issuer = "someone-else"), clock)

        assertThat(service.verify(other.issue(42L, UserRole.USER))).isNull()
    }

    @Test
    fun `형식이 깨진 문자열은 예외 없이 null 이다`() {
        listOf("", " ", "not-a-jwt", "a.b.c", "a.b", "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiJ9.").forEach {
            assertThat(service.verify(it)).`as`("'$it'").isNull()
        }
    }

    companion object {
        private const val SECRET = "unit-test-secret-0123456789-abcdefghij"
    }
}
