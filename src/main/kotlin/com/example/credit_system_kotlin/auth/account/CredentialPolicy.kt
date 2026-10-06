package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.global.exception.InvalidRequestException

/** 계정에 받아 주는 이메일과 비밀번호의 모양. 가입과 시드 계정이 같은 규칙을 쓴다. */
internal object CredentialPolicy {

    private const val MAX_EMAIL_LENGTH = 255
    private const val MIN_PASSWORD_LENGTH = 8
    private const val MAX_PASSWORD_BYTES = 72

    // 주소가 실제로 있는지는 확인하지 않는다. "@ 하나, 양쪽이 비어 있지 않고 도메인에 점"만 본다.
    private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /** [normalized] 는 `normalizeEmail` 을 거친 값이어야 한다. */
    fun validateEmail(normalized: String) {
        if (normalized.isEmpty()) {
            throw InvalidRequestException("이메일은 필수입니다.")
        }
        if (normalized.length > MAX_EMAIL_LENGTH) {
            throw InvalidRequestException("이메일은 ${MAX_EMAIL_LENGTH}자를 초과할 수 없습니다.")
        }
        if (!EMAIL_PATTERN.matches(normalized)) {
            throw InvalidRequestException("이메일 형식이 올바르지 않습니다.")
        }
    }

    /** 하한은 글자 수, 상한은 UTF-8 바이트로 잰다. 한글 비밀번호는 72자보다 훨씬 짧게 끊긴다. */
    fun validatePassword(rawPassword: String) {
        if (rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw InvalidRequestException("비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다.")
        }
        // BCrypt 는 72바이트 뒤를 버린다. 받아 주면 뒷부분이 달라도 로그인되는 비밀번호가 생긴다.
        if (rawPassword.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw InvalidRequestException("비밀번호가 너무 깁니다. 영문 기준 ${MAX_PASSWORD_BYTES}자까지 쓸 수 있습니다.")
        }
    }
}
