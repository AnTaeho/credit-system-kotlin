package com.example.credit_system_kotlin.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.auth")
data class AuthProperties(
    val cookieSecure: Boolean = false
)

fun normalizeEmail(email: String): String = email.trim().lowercase()

fun maskEmail(email: String?): String {
    if (email.isNullOrBlank()) return "(없음)"
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    return email.first() + "***" + email.substring(at)
}
