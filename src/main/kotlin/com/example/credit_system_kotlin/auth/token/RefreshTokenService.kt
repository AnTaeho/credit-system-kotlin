package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.HexFormat

private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)

/** 리프레시 토큰은 로그인할 때 한 장 낸다. 만료되거나 로그아웃으로 지워질 때까지 그 한 장으로 액세스를 새로 받는다. */
@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock
) {

    private val secureRandom = SecureRandom()

    /** 256비트 난수로 원문을 만들어 돌려주고 DB 에는 해시만 남긴다. 원문은 이 뒤로 다시 얻을 수 없다. */
    @Transactional
    fun issue(userId: Long): String {
        val bytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        refreshTokenRepository.save(
            RefreshToken(userId, sha256Hex(raw), clock.instant().plus(jwtProperties.refreshTtl))
        )
        log.info("리프레시 토큰 발급: userId={}", userId)
        return raw
    }

    /** 유효한 토큰이면 그 주인의 id 를 준다. 모르는 토큰이거나 만료됐으면 null 이다. */
    @Transactional(readOnly = true)
    fun userIdOf(raw: String): Long? {
        val token = refreshTokenRepository.findByTokenHash(sha256Hex(raw))
        if (token == null) {
            log.info("리프레시 거절: 모르는 토큰")
            return null
        }
        if (token.expiresAt <= clock.instant()) {
            log.info("리프레시 거절: 만료된 토큰, userId={}", token.userId)
            return null
        }
        return token.userId
    }

    /** 로그아웃. 그 토큰 한 장을 지운다. 모르는 토큰이면 할 일이 없다. */
    @Transactional
    fun delete(raw: String) {
        val deleted = refreshTokenRepository.deleteByTokenHash(sha256Hex(raw))
        log.info("리프레시 토큰 삭제(로그아웃): deleted={}", deleted)
    }

    companion object {
        private const val TOKEN_BYTES = 32

        /** 원문은 256비트 난수라 무차별 대입이 불가능하다. 느린 해시(BCrypt)가 필요 없고, 조회 키로 쓸 수 있어야 한다. */
        fun sha256Hex(raw: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    }
}
