package com.example.credit_system_kotlin.auth.token

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

// 리프레시 토큰을 Redis 에 `refresh:{jti}` → 사용자 id 로 둔다. 만료는 Redis TTL 이 맡는다.
// Redis 예외는 그대로 올린다. 삼킬지 올릴지는 부르는 쪽(RefreshTokenService)이 흐름마다 정한다.
@Component
class RefreshTokenStore(private val redisTemplate: StringRedisTemplate) {

    fun save(jti: String, userId: Long, ttl: Duration) {
        redisTemplate.opsForValue().set(keyOf(jti), userId.toString(), ttl)
    }

    // 키가 없으면(로그아웃·만료·Redis 초기화) null 이다.
    fun findUserId(jti: String): String? = redisTemplate.opsForValue().get(keyOf(jti))

    // 지운 키가 있었으면 true 다.
    fun delete(jti: String): Boolean = redisTemplate.delete(keyOf(jti))

    companion object {
        const val KEY_PREFIX = "refresh:"

        fun keyOf(jti: String): String = KEY_PREFIX + jti
    }
}
