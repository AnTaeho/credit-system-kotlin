package com.example.credit_system_kotlin.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.auth.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "credit",
    val accessTtl: Duration = Duration.ofHours(1),
    val refreshTtl: Duration = Duration.ofDays(14)
) {

    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "app.auth.jwt.secret 은 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다."
        }
        require(issuer.isNotBlank()) { "app.auth.jwt.issuer 는 비어 있을 수 없습니다." }
        require(accessTtl > Duration.ZERO) { "app.auth.jwt.access-ttl 은 0보다 커야 합니다." }
        require(refreshTtl > accessTtl) { "app.auth.jwt.refresh-ttl 은 access-ttl 보다 길어야 합니다." }
    }

    override fun toString(): String =
        "JwtProperties(secret=***, issuer=$issuer, accessTtl=$accessTtl, refreshTtl=$refreshTtl)"

    companion object {
        const val MIN_SECRET_BYTES = 32
    }
}
