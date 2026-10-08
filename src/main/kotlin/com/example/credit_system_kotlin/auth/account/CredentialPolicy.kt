package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.auth.dto.SignUpRequest
import com.example.credit_system_kotlin.global.exception.InvalidRequestException

internal object CredentialPolicy {

    private const val MAX_EMAIL_LENGTH = 255
    private const val MIN_PASSWORD_LENGTH = 8
    private const val MAX_PASSWORD_BYTES = 72

    private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    // 가입 요청을 한 번에 검증하고, 저장에 쓸 정규화된 이메일과 비밀번호를 돌려준다.
    // 폼에서 비어 온 값(null)은 빈 문자열로 보고 같은 검증에 걸리게 한다.
    fun validate(request: SignUpRequest): ValidCredentials {
        val email = normalizeEmail(request.email.orEmpty())
        val rawPassword = request.password.orEmpty()
        validateEmail(email)
        validatePassword(rawPassword)
        return ValidCredentials(email, rawPassword)
    }

    private fun validateEmail(normalized: String) {
        if (normalized.isEmpty()) {
            throw InvalidRequestException.emailRequired()
        }
        if (normalized.length > MAX_EMAIL_LENGTH) {
            throw InvalidRequestException.emailTooLong(MAX_EMAIL_LENGTH)
        }
        if (!EMAIL_PATTERN.matches(normalized)) {
            throw InvalidRequestException.emailMalformed()
        }
    }

    private fun validatePassword(rawPassword: String) {
        if (rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw InvalidRequestException.passwordTooShort(MIN_PASSWORD_LENGTH)
        }
        if (rawPassword.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw InvalidRequestException.passwordTooLong(MAX_PASSWORD_BYTES)
        }
    }
}

internal class ValidCredentials(val email: String, val rawPassword: String)
