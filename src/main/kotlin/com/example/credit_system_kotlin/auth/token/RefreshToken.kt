package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.global.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/**
 * 리프레시 토큰 한 장. 원문은 클라이언트만 갖고 여기에는 SHA-256 해시만 둔다.
 * [rotatedAt]·[revokedAt] 은 [RefreshTokenRepository] 의 조건부 UPDATE 로만 채운다. 경쟁을 UPDATE 한 줄로 가른다.
 */
@Entity
@Table(
    name = "refresh_tokens",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = ["tokenHash"])
    ],
    indexes = [
        Index(name = "idx_refresh_tokens_family_id", columnList = "familyId"),
        Index(name = "idx_refresh_tokens_user_id", columnList = "userId"),
        Index(name = "idx_refresh_tokens_expires_at", columnList = "expiresAt")
    ]
)
class RefreshToken(

    @Column(nullable = false)
    val userId: Long,

    /** 원문 토큰의 SHA-256 hex. */
    @Column(nullable = false, length = 64)
    val tokenHash: String,

    /** 한 번의 로그인에서 이어진 회전 사슬. 회전으로 나온 토큰은 앞 토큰의 값을 물려받는다. */
    @Column(nullable = false, length = 36)
    val familyId: String,

    @Column(nullable = false)
    val expiresAt: Instant

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 RefreshToken입니다." }

    /** 다음 토큰으로 교체된 시각. */
    var rotatedAt: Instant? = null
        protected set

    var revokedAt: Instant? = null
        protected set
}
