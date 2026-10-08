package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.support.jwtClaim
import com.example.credit_system_kotlin.support.jwtPayload
import com.example.credit_system_kotlin.user.domain.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.time.Instant

// 실제 Redis(Testcontainers)에 대고 발급·확인·삭제를 본다. DB 는 쓰지 않는다.
@ActiveProfiles("test")
@SpringBootTest
class RefreshTokenServiceTest @Autowired constructor(
    private val redisTemplate: StringRedisTemplate
) {

    private lateinit var clock: FixedMutableClock
    private lateinit var codec: JwtCodec
    private lateinit var service: RefreshTokenService

    @BeforeEach
    fun setUp() {
        clock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        codec = JwtCodec(PROPERTIES, clock)
        service = RefreshTokenService(codec, RefreshTokenStore(redisTemplate), PROPERTIES)
    }

    @AfterEach
    fun tearDown() {
        redisTemplate.delete(redisTemplate.keys("${RefreshTokenStore.KEY_PREFIX}*"))
    }

    @Test
    fun `발급한 토큰은 주인의 사용자 id 를 준다`() {
        val token = service.issue(USER_ID)
        val other = service.issue(OTHER_USER_ID)

        assertThat(service.check(token)).isEqualTo(RefreshCheck.Valid(USER_ID))
        assertThat(service.check(other)).isEqualTo(RefreshCheck.Valid(OTHER_USER_ID))
    }

    @Test
    fun `발급한 토큰은 sub jti typ iat exp 를 가진 JWT 이고 수명은 refreshTtl 이다`() {
        val payload = jwtPayload(service.issue(USER_ID))

        assertThat(jwtClaim(payload, "sub")).isEqualTo(USER_ID.toString())
        assertThat(jwtClaim(payload, "typ")).isEqualTo("refresh")
        assertThat(jwtClaim(payload, "jti")).hasSize(36)
        val iat = requireNotNull(Regex("\"iat\":(\\d+)").find(payload)).groupValues[1].toLong()
        val exp = requireNotNull(Regex("\"exp\":(\\d+)").find(payload)).groupValues[1].toLong()
        assertThat(iat).isEqualTo(clock.instant().epochSecond)
        assertThat(exp - iat).isEqualTo(REFRESH_TTL.seconds)
        assertThat(payload).doesNotContain("role")
    }

    @Test
    fun `발급하면 Redis 에 refresh jti 키가 사용자 id 값으로 생기고 TTL 은 refreshTtl 근처다`() {
        val token = service.issue(USER_ID)

        val key = keyOf(token)
        assertThat(key).startsWith("refresh:")
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(USER_ID.toString())
        assertThat(redisTemplate.getExpire(key))
            .isBetween(REFRESH_TTL.seconds - TTL_SLACK_SECONDS, REFRESH_TTL.seconds)
    }

    @Test
    fun `로그인마다 다른 토큰과 다른 키가 나온다`() {
        val first = service.issue(USER_ID)
        val second = service.issue(USER_ID)

        assertThat(first).isNotEqualTo(second)
        assertThat(keyOf(first)).isNotEqualTo(keyOf(second))
        assertThat(redisTemplate.hasKey(keyOf(first))).isTrue()
        assertThat(redisTemplate.hasKey(keyOf(second))).isTrue()
    }

    @Test
    fun `같은 토큰을 여러 번 써도 계속 유효하고 키는 그대로다`() {
        val token = service.issue(USER_ID)

        repeat(3) {
            clock.advance(Duration.ofDays(1))
            assertThat(service.check(token)).isEqualTo(RefreshCheck.Valid(USER_ID))
        }
        assertThat(redisTemplate.keys("refresh:*")).containsExactly(keyOf(token))
    }

    @Test
    fun `삭제한 토큰은 무효이고 Redis 키도 없다`() {
        val token = service.issue(USER_ID)

        service.delete(token)

        assertThat(service.check(token)).isEqualTo(RefreshCheck.Invalid)
        assertThat(redisTemplate.hasKey(keyOf(token))).isFalse()
    }

    @Test
    fun `다른 기기의 토큰은 한쪽을 지워도 남는다`() {
        val token = service.issue(USER_ID)
        val otherDevice = service.issue(USER_ID)

        service.delete(token)

        assertThat(service.check(token)).isEqualTo(RefreshCheck.Invalid)
        assertThat(service.check(otherDevice)).isEqualTo(RefreshCheck.Valid(USER_ID))
        assertThat(redisTemplate.hasKey(keyOf(otherDevice))).isTrue()
    }

    // Redis 키는 실제 시간으로 만료되므로 아직 남아 있다. JWT 의 exp 만으로 거절되는지 본다.
    @Test
    fun `만료 직전까지는 유효하고 만료 시각을 지나면 Redis 에 키가 남아 있어도 무효다`() {
        val token = service.issue(USER_ID)

        clock.advance(REFRESH_TTL.minusSeconds(1))
        assertThat(service.check(token)).isEqualTo(RefreshCheck.Valid(USER_ID))
        // 검증기는 exp 를 지난 뒤부터 만료로 본다(액세스 토큰과 같다). exp 와 같은 초까지는 유효하다.
        clock.advance(Duration.ofSeconds(2))
        assertThat(service.check(token)).isEqualTo(RefreshCheck.Invalid)
        assertThat(redisTemplate.hasKey(keyOf(token))).isTrue()
    }

    // 다른 키로 서명한 쪽도 같은 Redis 에 저장하므로 키는 있다. 서명만으로 거절되는지 본다.
    @Test
    fun `서명이 다른 JWT 는 Redis 에 키가 있어도 무효다`() {
        val otherProperties = PROPERTIES.copy(secret = "another-secret-0123456789-abcdefghij")
        val otherCodec = JwtCodec(otherProperties, clock)
        val other = RefreshTokenService(otherCodec, RefreshTokenStore(redisTemplate), otherProperties)
        val forged = other.issue(USER_ID)

        assertThat(redisTemplate.hasKey(keyOf(forged))).isTrue()
        assertThat(service.check(forged)).isEqualTo(RefreshCheck.Invalid)
    }

    @Test
    fun `액세스 JWT 를 리프레시로 내면 무효다`() {
        val access = AccessTokenService(codec, PROPERTIES).issue(USER_ID, UserRole.USER)
        // jti 가 Redis 에 있는 액세스 토큰이어도 종류가 달라 받지 않는다.
        val token = service.issue(USER_ID)
        val jti = requireNotNull(jwtClaim(token, "jti"))
        val accessWithJti = codec.encode(TokenType.ACCESS, USER_ID, REFRESH_TTL) { id(jti) }

        assertThat(service.check(access)).isEqualTo(RefreshCheck.Invalid)
        assertThat(service.check(accessWithJti)).isEqualTo(RefreshCheck.Invalid)
    }

    @Test
    fun `Redis 에 없는 jti 는 서명이 맞아도 무효다`() {
        val token = service.issue(USER_ID)
        // Redis 가 비워진 경우다.
        redisTemplate.delete(keyOf(token))
        val neverStored = codec.encode(TokenType.REFRESH, USER_ID, REFRESH_TTL) { id("never-stored") }

        assertThat(service.check(token)).isEqualTo(RefreshCheck.Invalid)
        assertThat(service.check(neverStored)).isEqualTo(RefreshCheck.Invalid)
    }

    @Test
    fun `Redis 의 값이 토큰의 sub 와 다르면 무효다`() {
        val token = service.issue(USER_ID)
        redisTemplate.opsForValue().set(keyOf(token), OTHER_USER_ID.toString())

        assertThat(service.check(token)).isEqualTo(RefreshCheck.Invalid)
    }

    @Test
    fun `형식이 깨진 문자열은 예외 없이 무효이고 지워도 다른 토큰에 영향이 없다`() {
        val token = service.issue(USER_ID)

        listOf("", "never-issued", "a.b.c").forEach {
            assertThat(service.check(it)).`as`("'$it'").isEqualTo(RefreshCheck.Invalid)
            service.delete(it)
        }
        assertThat(service.check(token)).isEqualTo(RefreshCheck.Valid(USER_ID))
    }

    @Test
    fun `Redis 조회가 예외를 내면 확인할 수 없음이고 무효와 구분된다`() {
        val token = service.issue(USER_ID)

        val result = serviceWithBrokenRedis().check(token)

        assertThat(result).isEqualTo(RefreshCheck.Unavailable).isNotEqualTo(RefreshCheck.Invalid)
        // JWT 부터 틀린 토큰은 Redis 를 보기 전에 무효로 끝난다.
        assertThat(serviceWithBrokenRedis().check("never-issued")).isEqualTo(RefreshCheck.Invalid)
        assertThat(service.check(token)).`as`("Redis 가 돌아오면 다시 유효").isEqualTo(RefreshCheck.Valid(USER_ID))
    }

    @Test
    fun `Redis 저장이 예외를 내면 발급은 그 예외를 그대로 올린다`() {
        assertThatThrownBy { serviceWithBrokenRedis().issue(USER_ID) }
            .isInstanceOf(RedisConnectionFailureException::class.java)
    }

    @Test
    fun `Redis 삭제가 예외를 내도 삭제는 예외를 올리지 않는다`() {
        val token = service.issue(USER_ID)

        serviceWithBrokenRedis().delete(token)

        assertThat(service.check(token)).`as`("지우지 못한 토큰은 남는다").isEqualTo(RefreshCheck.Valid(USER_ID))
    }

    private fun serviceWithBrokenRedis(): RefreshTokenService {
        val down = RedisConnectionFailureException("redis down")
        val operations = mock<ValueOperations<String, String>> {
            on { get(any()) } doThrow down
            on { set(any(), any(), any<Duration>()) } doThrow down
        }
        val broken = mock<StringRedisTemplate> {
            on { opsForValue() } doReturn operations
            on { delete(any<String>()) } doThrow down
        }
        return RefreshTokenService(codec, RefreshTokenStore(broken), PROPERTIES)
    }

    private fun keyOf(token: String): String = RefreshTokenStore.keyOf(requireNotNull(jwtClaim(token, "jti")))

    companion object {
        // 토큰은 사용자 행을 참조만 하므로 실제 행이 없어도 된다.
        private const val USER_ID = 1L
        private const val OTHER_USER_ID = 2L
        private const val TTL_SLACK_SECONDS = 60L
        private val REFRESH_TTL: Duration = Duration.ofDays(14)
        private val PROPERTIES =
            JwtProperties(secret = "unit-test-secret-0123456789-abcdefghij", refreshTtl = REFRESH_TTL)

        @JvmStatic
        @DynamicPropertySource
        fun redisProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerRedis(registry)
        }
    }
}
