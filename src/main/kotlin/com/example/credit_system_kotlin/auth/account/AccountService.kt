package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.global.exception.EmailAlreadyUsedException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(AccountService::class.java)

/** 이메일·비밀번호 가입과 비밀번호 확인. 토큰 발급은 호출자가 [authenticate] 결과를 받아서 한다. */
@Service
class AccountService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    /**
     * 없는 이메일로 로그인할 때 대신 비교할 해시. 가입된 이메일만 BCrypt 를 돌리면 응답 시간 차로
     * 가입 여부가 드러난다. 실제 해시와 같은 인코더로 만들어 걸리는 시간을 맞춘다.
     */
    private val dummyHash: String = requireNotNull(passwordEncoder.encode(DUMMY_PASSWORD))

    @Transactional
    fun signUp(email: String, rawPassword: String): User {
        val normalized = normalizeEmail(email)
        validateEmail(normalized)
        validatePassword(rawPassword)

        if (userRepository.findByEmail(normalized) != null) {
            log.info("가입 거절: 이미 가입된 이메일, email={}", maskEmail(normalized))
            throw EmailAlreadyUsedException()
        }
        val passwordHash = requireNotNull(passwordEncoder.encode(rawPassword))
        val created = try {
            userRepository.saveAndFlush(User.signUp(normalized, passwordHash))
        } catch (e: DataIntegrityViolationException) {
            // 같은 이메일로 동시에 가입하면 둘 다 "없음"을 보고 INSERT 해 한쪽이 uk_users_email 에 걸린다.
            // 그대로 두면 전역 핸들러가 멱등키 충돌(DUPLICATE_IN_PROGRESS)로 번역한다.
            // 원인 메시지에는 이메일이 그대로 들어 있어 남기지 않는다.
            log.info("가입 거절: 동시 가입 경합, email={}", maskEmail(normalized))
            throw EmailAlreadyUsedException()
        }
        log.info("가입: userId={}, email={}", created.persistedId, maskEmail(normalized))
        return created
    }

    /** 이메일과 비밀번호가 맞으면 그 사용자, 아니면 null. 왜 틀렸는지는 호출자에게도 알리지 않는다. */
    @Transactional(readOnly = true)
    fun authenticate(email: String, rawPassword: String): User? {
        val normalized = normalizeEmail(email)
        val user = userRepository.findByEmail(normalized)
        // 비밀번호 없이 만들어진 옛 행(구글·개발 로그인)도 없는 이메일과 똑같이 취급한다.
        val matched = matchesSafely(rawPassword, user?.passwordHash ?: dummyHash)
        if (user?.passwordHash == null || !matched) {
            log.info("로그인 실패: email={}", maskEmail(normalized))
            return null
        }
        return user
    }

    /** 72바이트를 넘는 입력에 BCrypt 인코더가 예외를 던진다. 로그인에서는 그것도 그냥 틀린 비밀번호다. */
    private fun matchesSafely(rawPassword: String, hash: String): Boolean =
        try {
            passwordEncoder.matches(rawPassword, hash)
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun validateEmail(normalized: String) {
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

    private fun validatePassword(rawPassword: String) {
        if (rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw InvalidRequestException("비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다.")
        }
        // BCrypt 는 72바이트 뒤를 버린다. 받아 주면 뒷부분이 달라도 로그인되는 비밀번호가 생긴다.
        if (rawPassword.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
            throw InvalidRequestException("비밀번호가 너무 깁니다. 영문 기준 ${MAX_PASSWORD_BYTES}자까지 쓸 수 있습니다.")
        }
    }

    companion object {
        private const val MAX_EMAIL_LENGTH = 255
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_PASSWORD_BYTES = 72
        private const val DUMMY_PASSWORD = "timing-equalizer-not-a-real-password"

        // 주소가 실제로 있는지는 확인하지 않는다. "@ 하나, 양쪽이 비어 있지 않고 도메인에 점"만 본다.
        private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}
