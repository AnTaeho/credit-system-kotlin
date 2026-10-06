package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

private val log = LoggerFactory.getLogger(RefreshTokenService::class.java)

/** [RefreshTokenService.rotate] 의 결과. 호출자는 이 넷을 보고 무엇을 새로 내줄지 정한다. */
sealed interface RotationResult {

    /** 이 요청이 회전을 차지했다. 액세스와 함께 [newRaw] 를 새 리프레시로 내준다. */
    data class Rotated(val userId: Long, val newRaw: String) : RotationResult {
        // 원문 토큰이 로그에 찍히지 않게 한다.
        override fun toString(): String = "Rotated(userId=$userId, newRaw=***)"
    }

    /**
     * 방금 다른 요청이 같은 토큰으로 회전을 차지했다(동시에 날아간 두 요청의 정상 경쟁).
     * 새 리프레시는 이긴 쪽 응답에 실려 있으므로 여기서는 주지 않는다. 액세스만 새로 낸다.
     */
    data class WithinGrace(val userId: Long) : RotationResult

    /** 유예 시간을 넘겨 이미 회전된 토큰이 다시 왔다. 탈취로 보고 사슬 전체를 폐기했다. */
    data object ReuseDetected : RotationResult

    /** 모르는 토큰이거나 만료·폐기됐다. */
    data object Rejected : RotationResult
}

/**
 * 리프레시 토큰은 쓸 때마다 새 것으로 바뀐다. 이미 쓴 토큰이 다시 오면 어느 쪽이 훔친 것인지 알 수 없어
 * 그 로그인의 사슬([RefreshToken.familyId])을 통째로 폐기한다. 탭 두 개의 동시 갱신은 유예 시간 안이면 봐준다.
 */
@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtProperties: JwtProperties,
    private val clock: Clock
) {

    private val secureRandom = SecureRandom()

    /** 로그인 직후 첫 토큰. 새 사슬을 연다. 돌려주는 값이 원문이고 DB 에는 해시만 남는다. */
    @Transactional
    fun issue(userId: Long): String {
        val familyId = UUID.randomUUID().toString()
        val raw = save(userId, familyId, clock.instant())
        log.info("리프레시 토큰 발급: userId={}, familyId={}", userId, familyId)
        return raw
    }

    /**
     * 조건부 UPDATE 를 조회보다 먼저 한다. 먼저 읽으면 REPEATABLE READ 스냅샷 탓에 진 쪽이 이긴 쪽의 회전을 못 본다.
     * UPDATE 와 INSERT 를 쪼개거나 순서를 바꾸면 동시에 도는 사슬 폐기를 새 토큰이 피해 간다(`ConcurrentReuseAndRotationTest`).
     */
    @Transactional
    fun rotate(raw: String): RotationResult {
        val tokenHash = sha256Hex(raw)
        val now = clock.instant()
        val won = refreshTokenRepository.markRotated(tokenHash, now) == 1
        val token = refreshTokenRepository.findByTokenHash(tokenHash)
        if (token == null) {
            log.info("리프레시 회전 거절: 모르는 토큰")
            return RotationResult.Rejected
        }
        if (won) {
            val newRaw = save(token.userId, token.familyId, now)
            log.info("리프레시 회전: userId={}, familyId={}", token.userId, token.familyId)
            return RotationResult.Rotated(token.userId, newRaw)
        }
        return classifyUnrotatable(token, now)
    }

    /** 로그아웃. 그 토큰이 속한 사슬 전체를 폐기한다. 모르는 토큰이면 할 일이 없다. */
    @Transactional
    fun revokeFamilyOf(raw: String) {
        val token = refreshTokenRepository.findByTokenHash(sha256Hex(raw)) ?: return
        val revoked = refreshTokenRepository.revokeFamily(token.familyId, clock.instant())
        log.info("리프레시 사슬 폐기(로그아웃): userId={}, familyId={}, revoked={}", token.userId, token.familyId, revoked)
    }

    /** 회전을 못 차지한 토큰을 가른다. 유예 안이면 동시 갱신으로 봐주고, 넘겼으면 재사용으로 보고 사슬을 폐기한다. */
    private fun classifyUnrotatable(token: RefreshToken, now: Instant): RotationResult {
        val rotatedAt = token.rotatedAt
        if (token.revokedAt != null) {
            // 사슬 폐기는 사슬의 모든 행에 revoked_at 을 채우므로 이 행만 봐도 사슬이 폐기됐는지 안다.
            log.info("리프레시 회전 거절: 폐기된 토큰, userId={}, familyId={}", token.userId, token.familyId)
            return RotationResult.Rejected
        }
        if (rotatedAt == null) {
            log.info("리프레시 회전 거절: 만료된 토큰, userId={}, familyId={}", token.userId, token.familyId)
            return RotationResult.Rejected
        }
        // 진 쪽의 now 가 이긴 쪽의 rotated_at 보다 앞설 수 있다(음수). 그것도 유예 안이다.
        if (Duration.between(rotatedAt, now) <= jwtProperties.refreshReuseGrace) {
            log.info("리프레시 동시 갱신(유예 안): userId={}, familyId={}", token.userId, token.familyId)
            return RotationResult.WithinGrace(token.userId)
        }
        val revoked = refreshTokenRepository.revokeFamily(token.familyId, now)
        log.warn(
            "리프레시 토큰 재사용 탐지 — 사슬 폐기: userId={}, familyId={}, revoked={}",
            token.userId, token.familyId, revoked
        )
        return RotationResult.ReuseDetected
    }

    /** 256비트 난수로 원문을 만들고 해시만 저장한다. 원문은 여기서 돌려준 뒤로 다시 얻을 수 없다. */
    private fun save(userId: Long, familyId: String, now: Instant): String {
        val bytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        refreshTokenRepository.save(
            RefreshToken(userId, sha256Hex(raw), familyId, now.plus(jwtProperties.refreshTtl))
        )
        return raw
    }

    companion object {
        private const val TOKEN_BYTES = 32

        /** 원문은 256비트 난수라 무차별 대입이 불가능하다. 느린 해시(BCrypt)가 필요 없고, 조회 키로 쓸 수 있어야 한다. */
        fun sha256Hex(raw: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)))
    }
}
