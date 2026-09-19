package com.example.credit_system_kotlin.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 누가 들어올 수 있는가(`allowed-emails`)와 그중 누가 운영자인가(`admin-emails`).
 *
 * 이메일은 대소문자를 가리지 않는다. 설정에 적힌 모양과 구글이 돌려주는 모양이 달라도
 * 같은 사람으로 본다. 비교는 전부 [normalizeEmail] 을 거친 값끼리 한다.
 */
@ConfigurationProperties(prefix = "app.auth")
data class AuthProperties(
    val allowedEmails: List<String> = emptyList(),
    val adminEmails: List<String> = emptyList(),
    val devLogin: DevLogin = DevLogin()
) {

    /** 로컬 스크립트·curl·테스트용 헤더 로그인. 기본은 꺼져 있다. */
    data class DevLogin(val enabled: Boolean = false)

    private val allowed: Set<String> = allowedEmails.map(::normalizeEmail).toSet()
    private val admins: Set<String> = adminEmails.map(::normalizeEmail).toSet()

    init {
        val notAllowedAdmins = admins - allowed
        require(notAllowedAdmins.isEmpty()) {
            "app.auth.admin-emails 는 app.auth.allowed-emails 에 포함돼야 합니다. " +
                "허용 목록에 없는 운영자 ${notAllowedAdmins.size}명"
        }
    }

    fun isAllowed(email: String): Boolean = normalizeEmail(email) in allowed

    fun isAdmin(email: String): Boolean = normalizeEmail(email) in admins

    fun hasNoAllowedEmails(): Boolean = allowed.isEmpty()
}

fun normalizeEmail(email: String): String = email.trim().lowercase()

/** 로그에 이메일을 그대로 남기지 않는다. `alice@example.com` → `a***@example.com` */
fun maskEmail(email: String?): String {
    if (email.isNullOrBlank()) return "(없음)"
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    return email.first() + "***" + email.substring(at)
}
