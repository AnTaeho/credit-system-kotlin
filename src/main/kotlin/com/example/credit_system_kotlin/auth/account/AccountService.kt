package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.auth.dto.SignUpRequest
import com.example.credit_system_kotlin.global.exception.EmailAlreadyUsedException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(AccountService::class.java)

@Service
class AccountService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {

    private val dummyHash: String = requireNotNull(passwordEncoder.encode(DUMMY_PASSWORD))

    @Transactional
    fun signUp(request: SignUpRequest): User {
        val credentials = CredentialPolicy.validate(request)
        val normalized = credentials.email
        val rawPassword = credentials.rawPassword

        if (userRepository.findByEmail(normalized) != null) {
            log.info("가입 거절: 이미 가입된 이메일, email={}", maskEmail(normalized))
            throw EmailAlreadyUsedException()
        }
        val passwordHash = requireNotNull(passwordEncoder.encode(rawPassword))
        val created = try {
            userRepository.saveAndFlush(User.signUp(normalized, passwordHash))
        } catch (_: DataIntegrityViolationException) {
            log.info("가입 거절: 동시 가입 경합, email={}", maskEmail(normalized))
            throw EmailAlreadyUsedException()
        }
        log.info("가입: userId={}, email={}", created.persistedId, maskEmail(normalized))
        return created
    }

    @Transactional(readOnly = true)
    fun authenticate(email: String, rawPassword: String): User? {
        val normalized = normalizeEmail(email)
        val user = userRepository.findByEmail(normalized)
        val matched = matchesSafely(rawPassword, user?.passwordHash ?: dummyHash)
        if (user?.passwordHash == null || !matched) {
            log.info("로그인 실패: email={}", maskEmail(normalized))
            return null
        }
        return user
    }

    private fun matchesSafely(rawPassword: String, hash: String): Boolean =
        try {
            passwordEncoder.matches(rawPassword, hash)
        } catch (e: IllegalArgumentException) {
            false
        }

    companion object {
        private const val DUMMY_PASSWORD = "timing-equalizer-not-a-real-password"
    }
}
