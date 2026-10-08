package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)

// 리프레시 토큰을 확인한 결과다. Redis 를 못 봐서 모르는 경우(Unavailable)를 무효(Invalid)와 구분한다.
sealed interface RefreshCheck {
    data class Valid(val userId: Long) : RefreshCheck
    data object Invalid : RefreshCheck
    data object Unavailable : RefreshCheck
}

// 리프레시 토큰은 jti 를 가진 JWT 이고, 그 jti 가 Redis 에 남아 있는 동안만 유효하다.
@Service
class RefreshTokenService(
    private val jwtCodec: JwtCodec,
    private val refreshTokenStore: RefreshTokenStore,
    private val jwtProperties: JwtProperties
) {

    // 로그인마다 새 jti 로 한 장을 낸다. Redis 저장이 실패하면 예외가 그대로 올라가 로그인이 실패한다.
    fun issue(userId: Long): String {
        val jti = UUID.randomUUID().toString()
        refreshTokenStore.save(jti, userId, jwtProperties.refreshTtl)
        log.info("리프레시 토큰 발급: userId={}", userId)
        return jwtCodec.encode(TokenType.REFRESH, userId, jwtProperties.refreshTtl) { id(jti) }
    }

    // JWT 의 서명·만료·종류를 본 뒤 Redis 에 그 jti 가 같은 사용자 id 로 남아 있는지 확인한다.
    fun check(token: String): RefreshCheck {
        val jwt = jwtCodec.decode(token, TokenType.REFRESH)
        val jti = jwt?.id
        val userId = jwt?.subject?.toLongOrNull()
        if (jti == null || userId == null) {
            log.info("리프레시 거절: JWT 가 유효하지 않음")
            return RefreshCheck.Invalid
        }
        val stored = try {
            refreshTokenStore.findUserId(jti)
        } catch (e: RuntimeException) {
            log.warn("리프레시 토큰 조회 실패, 확인할 수 없음으로 처리: userId={}", userId, e)
            return RefreshCheck.Unavailable
        }
        if (stored != userId.toString()) {
            log.info("리프레시 거절: Redis 에 없는 토큰, userId={}", userId)
            return RefreshCheck.Invalid
        }
        return RefreshCheck.Valid(userId)
    }

    // 로그아웃한 토큰의 jti 를 Redis 에서 지운다. 서명이 틀리거나 만료된 토큰은 지울 것이 없다.
    fun delete(token: String) {
        val jti = jwtCodec.decode(token, TokenType.REFRESH)?.id ?: return
        try {
            val deleted = refreshTokenStore.delete(jti)
            log.info("리프레시 토큰 삭제(로그아웃): deleted={}", deleted)
        } catch (e: RuntimeException) {
            log.warn("리프레시 토큰 삭제 실패, Redis 에 만료까지 남는다", e)
        }
    }
}
