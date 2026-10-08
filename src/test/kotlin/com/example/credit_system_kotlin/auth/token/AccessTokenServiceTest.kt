package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.jwtClaim
import com.example.credit_system_kotlin.support.jwtPayload
import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AccessTokenServiceTest {

    private val clock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val properties = JwtProperties(secret = SECRET, accessTtl = Duration.ofMinutes(15))
    private val service = accessTokenService(properties)
    private val codec = JwtCodec(properties, clock)

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
        val other = accessTokenService(properties.copy(secret = "another-secret-0123456789-abcdefghij"))

        assertThat(service.verify(other.issue(42L, UserRole.USER))).isNull()
    }

    @Test
    fun `issuer 가 다른 토큰은 null 이다`() {
        val other = accessTokenService(properties.copy(issuer = "someone-else"))

        assertThat(service.verify(other.issue(42L, UserRole.USER))).isNull()
    }

    @Test
    fun `형식이 깨진 문자열은 예외 없이 null 이다`() {
        listOf("", " ", "not-a-jwt", "a.b.c", "a.b", "eyJhbGciOiJub25lIn0.eyJzdWIiOiI0MiJ9.").forEach {
            assertThat(service.verify(it)).`as`("'$it'").isNull()
        }
    }

    @Test
    fun `리프레시 JWT 를 액세스로 내면 서명이 맞아도 null 이다`() {
        val codec = JwtCodec(properties, clock)
        val refresh = codec.encode(TokenType.REFRESH, 42L, properties.refreshTtl) { id("some-jti") }
        // 종류만 다르고 role 까지 갖춘 토큰도 받지 않는다.
        val refreshWithRole = codec.encode(TokenType.REFRESH, 42L, properties.refreshTtl) {
            claim(AccessTokenService.ROLE_CLAIM, UserRole.ADMIN.name)
        }

        assertThat(codec.decode(refresh, TokenType.REFRESH)).`as`("리프레시로는 유효한 토큰").isNotNull()
        assertThat(service.verify(refresh)).isNull()
        assertThat(service.verify(refreshWithRole)).isNull()
    }

    @Test
    fun `서명은 맞지만 role 이 없는 토큰은 null 이다`() {
        val token = codec.encode(TokenType.ACCESS, 42L, properties.accessTtl)

        assertThat(service.verify(token)).isNull()
    }

    @Test
    fun `서명은 맞지만 role 이 모르는 값인 토큰은 null 이다`() {
        val token = codec.encode(TokenType.ACCESS, 42L, properties.accessTtl) {
            claim(AccessTokenService.ROLE_CLAIM, "SUPERUSER")
        }

        assertThat(service.verify(token)).isNull()
    }

    @Test
    fun `서명은 맞지만 sub 가 숫자가 아닌 토큰은 null 이다`() {
        val token = codec.encode(TokenType.ACCESS, 42L, properties.accessTtl) {
            subject("not-a-number")
            claim(AccessTokenService.ROLE_CLAIM, UserRole.USER.name)
        }

        assertThat(jwtClaim(token, "sub")).isEqualTo("not-a-number")
        assertThat(service.verify(token)).isNull()
    }

    @Test
    fun `액세스 토큰의 클레임은 iss sub typ role iat exp 다`() {
        val payload = jwtPayload(service.issue(42L, UserRole.USER))

        assertThat(jwtClaim(payload, "sub")).isEqualTo("42")
        assertThat(jwtClaim(payload, "typ")).isEqualTo("access")
        assertThat(jwtClaim(payload, "role")).isEqualTo("USER")
        assertThat(payload).contains("\"iss\":\"credit\"", "\"iat\":", "\"exp\":").doesNotContain("\"jti\"")
    }

    private fun accessTokenService(properties: JwtProperties) =
        AccessTokenService(JwtCodec(properties, clock), properties)

    companion object {
        private const val SECRET = "unit-test-secret-0123456789-abcdefghij"
    }
}
