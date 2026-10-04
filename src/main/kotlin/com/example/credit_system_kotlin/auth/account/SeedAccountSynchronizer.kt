package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.auth.token.RefreshTokenRepository
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

private val log = LoggerFactory.getLogger(SeedAccountSynchronizer::class.java)

/**
 * 설정에 적힌 계정(`app.auth.seed-accounts`)을 사용자 행에 맞춘다. 운영자 계정이 생기는 유일한 길이다.
 *
 * - 그 이메일의 행이 없으면 만든다.
 * - 있으면 **설정이 기준이다.** 역할과 비밀번호를 설정값으로 덮는다. 누군가 운영자 이메일로 먼저 가입해 둔
 *   행이 있어도, 기동 뒤에는 그 사람이 정한 비밀번호로는 들어올 수 없다.
 * - 덮어썼으면 그 사용자의 리프레시 토큰을 모두 폐기한다. 덮이기 전의 비밀번호로 열린 로그인을 끊는다.
 *   이미 나간 액세스 토큰은 서버에 저장하지 않아 무효로 만들 수 없고, 만료(기본 15분)까지 예전 역할로 남는다.
 * - 역할과 비밀번호가 이미 설정과 같으면 아무것도 하지 않는다. 재기동할 때마다 로그아웃시키지 않는다.
 */
@Component
class SeedAccountSynchronizer(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val clock: Clock
) {

    @Transactional
    fun sync(account: AuthProperties.SeedAccount) {
        val email = normalizeEmail(account.email)
        validate(email, account.password)

        val existing = userRepository.findByEmail(email)
        if (existing == null) {
            val created = userRepository.save(newUser(email, encode(account.password), account.role))
            log.info("시드 계정 생성: userId={}, email={}, role={}", created.persistedId, maskEmail(email), account.role)
            return
        }

        val roleDiffers = existing.role != account.role
        val passwordDiffers = existing.passwordHash?.let { !passwordEncoder.matches(account.password, it) } ?: true
        if (!roleDiffers && !passwordDiffers) {
            return
        }
        if (roleDiffers) {
            existing.changeRole(account.role)
        }
        if (passwordDiffers) {
            existing.changePassword(encode(account.password))
        }
        // 폐기 UPDATE 가 영속성 컨텍스트를 비우기 전에 위 변경을 내보낸다(flushAutomatically).
        val userId = existing.persistedId
        val revoked = refreshTokenRepository.revokeAllOfUser(userId, clock.instant())
        log.warn(
            "시드 계정을 설정값으로 덮음: userId={}, email={}, role={}, 역할 변경={}, 비밀번호 변경={}, 폐기한 리프레시={}",
            userId, maskEmail(email), account.role, roleDiffers, passwordDiffers, revoked
        )
    }

    /** 시드 계정도 가입과 같은 규칙을 따른다. 어기면 기동을 멈춘다 — 로그인할 수 없는 계정을 조용히 만들지 않는다. */
    private fun validate(email: String, rawPassword: String) {
        try {
            CredentialPolicy.validateEmail(email)
            CredentialPolicy.validatePassword(rawPassword)
        } catch (e: InvalidRequestException) {
            throw IllegalStateException("app.auth.seed-accounts 의 ${maskEmail(email)} 계정이 올바르지 않습니다. ${e.message}", e)
        }
    }

    private fun encode(rawPassword: String): String = requireNotNull(passwordEncoder.encode(rawPassword))

    private fun newUser(email: String, passwordHash: String, role: UserRole): User =
        when (role) {
            UserRole.ADMIN -> User.admin(email, passwordHash)
            UserRole.USER -> User.signUp(email, passwordHash)
        }
}

/** 기동이 끝나면 한 번 맞춘다. 스키마(Flyway)가 선 뒤에 돈다. */
@Component
class SeedAccountRunner(
    private val authProperties: AuthProperties,
    private val synchronizer: SeedAccountSynchronizer
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        authProperties.seedAccounts.forEach(synchronizer::sync)
    }
}
