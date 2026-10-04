package com.example.credit_system_kotlin.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 액세스 JWT 와 리프레시 토큰의 수명·서명 설정.
 *
 * [refreshReuseGrace] 는 이미 회전된 리프레시 토큰이 다시 와도 탈취로 보지 않는 시간이다. 탭 두 개가 같은
 * 토큰을 들고 동시에 갱신을 요청하는 정상 경쟁을 재사용 탐지와 구분하려고 둔다.
 */
@ConfigurationProperties(prefix = "app.auth.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "credit",
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(14),
    val refreshReuseGrace: Duration = Duration.ofSeconds(10)
) {

    init {
        // HS256 은 키가 해시 출력(256비트)보다 짧으면 그만큼 약해진다. 짧은 키로는 뜨지 않게 한다.
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "app.auth.jwt.secret 은 ${MIN_SECRET_BYTES}바이트 이상이어야 합니다."
        }
        require(issuer.isNotBlank()) { "app.auth.jwt.issuer 는 비어 있을 수 없습니다." }
        require(accessTtl > Duration.ZERO) { "app.auth.jwt.access-ttl 은 0보다 커야 합니다." }
        require(refreshTtl > accessTtl) { "app.auth.jwt.refresh-ttl 은 access-ttl 보다 길어야 합니다." }
        require(!refreshReuseGrace.isNegative) { "app.auth.jwt.refresh-reuse-grace 는 음수일 수 없습니다." }
    }

    // data class 의 기본 toString 은 secret 을 그대로 찍는다. 로그나 예외 메시지로 새지 않게 가린다.
    override fun toString(): String =
        "JwtProperties(secret=***, issuer=$issuer, accessTtl=$accessTtl, " +
            "refreshTtl=$refreshTtl, refreshReuseGrace=$refreshReuseGrace)"

    companion object {
        const val MIN_SECRET_BYTES = 32

        /**
         * `application.yml` 이 `APP_AUTH_JWT_SECRET` 이 없을 때 쓰는 로컬 기본값과 같은 글자다. 저장소에 공개된
         * 키라 운영에서 쓰이면 안 되고, [AuthStartupGuard] 가 prod 에서 이 값을 거부한다. yml 을 바꾸면 여기도 바꾼다.
         */
        const val LOCAL_DEFAULT_SECRET = "local-only-jwt-secret-do-not-use-in-prod"
    }
}
