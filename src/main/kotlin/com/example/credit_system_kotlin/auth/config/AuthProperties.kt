package com.example.credit_system_kotlin.auth.config

import com.example.credit_system_kotlin.user.domain.UserRole
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 로그인 쿠키의 `Secure` 여부와, 기동할 때 맞춰 두는 계정([seedAccounts]).
 *
 * 가입은 누구나 하고 항상 일반 사용자다. 운영자는 가입으로 만들 수 없다 — 이메일 소유를 확인하지 않으므로
 * "이 이메일로 가입하면 운영자"라는 규칙을 두면 남이 먼저 가입해 권한을 가져간다. 그래서 운영자는 설정에 적은
 * 계정을 기동 단계에서 만들어 두는 길 하나뿐이다
 * ([com.example.credit_system_kotlin.auth.account.SeedAccountSynchronizer]).
 *
 * 토큰의 수명과 서명 키는 [JwtProperties] 가 따로 갖는다.
 */
@ConfigurationProperties(prefix = "app.auth")
data class AuthProperties(
    /** 로그인 쿠키에 `Secure` 를 붙인다. HTTPS 인 운영에서 켠다. 꺼 둔 채 prod 로 뜨면 [AuthStartupGuard] 가 막는다. */
    val cookieSecure: Boolean = false,
    val seedAccounts: List<SeedAccount> = emptyList()
) {

    data class SeedAccount(
        val email: String,
        val password: String,
        val role: UserRole = UserRole.USER
    ) {
        // data class 의 기본 toString 은 비밀번호를 그대로 찍는다. 로그나 예외 메시지로 새지 않게 가린다.
        override fun toString(): String = "SeedAccount(email=${maskEmail(email)}, password=***, role=$role)"
    }

    init {
        val emails = seedAccounts.map { normalizeEmail(it.email) }
        require(emails.size == emails.toSet().size) {
            "app.auth.seed-accounts 에 같은 이메일이 두 번 적혀 있습니다."
        }
    }
}

/** 이메일은 대소문자를 가리지 않는다. 저장과 비교는 전부 이 함수를 거친 값끼리 한다. */
fun normalizeEmail(email: String): String = email.trim().lowercase()

/** 로그에 이메일을 그대로 남기지 않는다. `alice@example.com` → `a***@example.com` */
fun maskEmail(email: String?): String {
    if (email.isNullOrBlank()) return "(없음)"
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    return email.first() + "***" + email.substring(at)
}
