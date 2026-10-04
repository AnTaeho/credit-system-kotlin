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
 * 리프레시 토큰을 내고, 돌리고(회전), 폐기한다.
 *
 * 토큰은 쓸 때마다 새 것으로 바뀐다. 같은 토큰이 두 번 오면 한쪽은 훔친 것일 수 있는데, 누가 진짜인지는
 * 알 수 없으므로 그 로그인에서 이어진 사슬([RefreshToken.familyId]) 전체를 폐기해 양쪽 모두 다시 로그인하게 한다.
 * 다만 탭 두 개가 동시에 갱신하는 정상 경쟁까지 로그아웃시키지 않도록 짧은 유예(`refresh-reuse-grace`)를 둔다.
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
     * **조건부 UPDATE 를 조회보다 먼저 한다.** MySQL(REPEATABLE READ)은 트랜잭션의 첫 SELECT 때 스냅샷을 잡는다.
     * 먼저 조회하면, 경쟁에서 진 쪽이 UPDATE 0행 뒤에 다시 읽어도 이긴 쪽의 `rotated_at` 이 보이지 않아
     * 정상 경쟁을 가려내지 못한다. UPDATE 는 항상 최신 행을 보므로 승패를 먼저 가르고, 그 뒤에 읽는다.
     *
     * **사슬 폐기와 겹칠 때.** 회전은 쓰던 토큰 행을 조건부 UPDATE 로 잠근 채 새 토큰을 INSERT 하고 한 트랜잭션으로
     * 커밋한다. 사슬 폐기([RefreshTokenRepository.revokeFamily])는 그 사슬의 폐기되지 않은 행을 모두 고치므로 같은
     * 행을 잠가야 한다. 그래서 둘은 그 행에서 줄을 선다. 폐기가 먼저면 회전의 UPDATE 가 `revoked_at` 을 보고 0행이
     * 되어 거절되고, 회전이 먼저면 폐기가 그 커밋을 기다린 뒤 새 토큰까지 고친다. 새 토큰 한 장이 폐기를 피해
     * 살아남는 일이 없도록 따로 다시 읽지 않는 이유다. `ConcurrentReuseAndRotationTest` 가 MySQL 에서 확인한다.
     * 회전을 두 트랜잭션으로 쪼개거나 INSERT 를 UPDATE 앞으로 옮기면 이 보장이 깨진다.
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
