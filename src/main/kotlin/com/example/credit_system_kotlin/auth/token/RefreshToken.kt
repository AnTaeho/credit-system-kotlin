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
 * 행이 있고 [expiresAt] 전이면 유효하다. 로그아웃은 행을 지운다.
 */
@Entity
@Table(
    name = "refresh_tokens",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = ["tokenHash"])
    ],
    indexes = [
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

    @Column(nullable = false)
    val expiresAt: Instant

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 RefreshToken입니다." }
}
