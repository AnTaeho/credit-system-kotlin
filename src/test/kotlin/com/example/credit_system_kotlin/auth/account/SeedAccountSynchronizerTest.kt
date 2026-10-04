package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.auth.config.AuthProperties.SeedAccount
import com.example.credit_system_kotlin.auth.token.RefreshToken
import com.example.credit_system_kotlin.auth.token.RefreshTokenRepository
import com.example.credit_system_kotlin.support.FixedMutableClock
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant

/**
 * 시드 계정 맞추기. 컨텍스트를 시드 설정으로 띄우지 않고 [SeedAccountSynchronizer] 를 직접 부른다 —
 * 띄우면 같은 H2 를 쓰는 다른 테스트에 계정이 남는다. 여기서는 테스트마다 롤백된다.
 */
@ActiveProfiles("test")
@DataJpaTest
class SeedAccountSynchronizerTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository
) {

    // 운영 기본 강도(10)는 테스트마다 수백 ms 가 든다. 동작은 같으므로 가장 낮은 강도로 돌린다.
    private val passwordEncoder = BCryptPasswordEncoder(4)

    private val clock = FixedMutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    private val accountService = AccountService(userRepository, passwordEncoder)

    private val synchronizer = SeedAccountSynchronizer(userRepository, refreshTokenRepository, passwordEncoder, clock)

    @Test
    fun `없는 이메일이면 설정의 역할과 비밀번호로 만든다`() {
        synchronizer.sync(SeedAccount(" Boss@Test.Local ", SEED_PASSWORD, UserRole.ADMIN))
        synchronizer.sync(SeedAccount("dev@test.local", SEED_PASSWORD, UserRole.USER))

        val boss = accountService.authenticate("boss@test.local", SEED_PASSWORD)
        assertThat(boss?.role).isEqualTo(UserRole.ADMIN)
        assertThat(boss?.balance).isZero()
        assertThat(accountService.authenticate("dev@test.local", SEED_PASSWORD)?.role).isEqualTo(UserRole.USER)
    }

    /** 운영자 이메일을 남이 먼저 가입해 둔 경우다. 기동 뒤에는 그 사람의 비밀번호도, 열어 둔 로그인도 쓸 수 없다. */
    @Test
    fun `같은 이메일로 먼저 가입한 일반 사용자는 설정의 역할과 비밀번호로 덮이고 리프레시가 폐기된다`() {
        val squatter = accountService.signUp("boss@test.local", "squatter-password")
        val squatterToken = refreshTokenOf(squatter.persistedId, "squatter")
        val bystanderToken = refreshTokenOf(squatter.persistedId + 1_000L, "bystander")

        synchronizer.sync(SeedAccount("boss@test.local", SEED_PASSWORD, UserRole.ADMIN))

        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(accountService.authenticate("boss@test.local", "squatter-password")).isNull()
        val boss = accountService.authenticate("boss@test.local", SEED_PASSWORD)
        assertThat(boss?.id).isEqualTo(squatter.persistedId)
        assertThat(boss?.role).isEqualTo(UserRole.ADMIN)
        assertThat(revokedAtOf(squatterToken)).isEqualTo(clock.instant())
        assertThat(revokedAtOf(bystanderToken)).`as`("다른 사용자의 토큰").isNull()
    }

    @Test
    fun `역할만 다르면 역할을 맞추고 비밀번호 해시는 그대로 두며 리프레시를 폐기한다`() {
        val user = accountService.signUp("boss@test.local", SEED_PASSWORD)
        val hashBefore = user.passwordHash
        val token = refreshTokenOf(user.persistedId, "before")

        synchronizer.sync(SeedAccount("boss@test.local", SEED_PASSWORD, UserRole.ADMIN))

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.role).isEqualTo(UserRole.ADMIN)
        assertThat(found.passwordHash).isEqualTo(hashBefore)
        assertThat(revokedAtOf(token)).isNotNull()
    }

    /** 재기동할 때마다 시드 계정이 로그아웃되면 안 된다. */
    @Test
    fun `이미 설정과 같으면 아무것도 바꾸지 않고 리프레시도 그대로 둔다`() {
        synchronizer.sync(SeedAccount("boss@test.local", SEED_PASSWORD, UserRole.ADMIN))
        val created = userRepository.findByEmail("boss@test.local")!!
        val hashBefore = created.passwordHash
        val token = refreshTokenOf(created.persistedId, "kept")

        synchronizer.sync(SeedAccount("boss@test.local", SEED_PASSWORD, UserRole.ADMIN))

        assertThat(userRepository.findByEmail("boss@test.local")!!.passwordHash).isEqualTo(hashBefore)
        assertThat(revokedAtOf(token)).isNull()
    }

    @Test
    fun `가입 규칙을 어긴 시드 계정은 만들지 않고 예외로 기동을 멈춘다`() {
        listOf(
            SeedAccount("boss@test.local", "1234567", UserRole.ADMIN),
            SeedAccount("boss@test.local", "a".repeat(73), UserRole.ADMIN),
            SeedAccount("not-an-email", SEED_PASSWORD, UserRole.ADMIN)
        ).forEach { account ->
            assertThatThrownBy { synchronizer.sync(account) }
                .`as`(account.toString())
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("app.auth.seed-accounts")
        }
        assertThat(userRepository.count()).isZero()
    }

    private fun refreshTokenOf(userId: Long, label: String): Long =
        refreshTokenRepository.saveAndFlush(
            RefreshToken(userId, "hash-$label", "family-$label", clock.instant().plus(Duration.ofDays(14)))
        ).persistedId

    private fun revokedAtOf(tokenId: Long): Instant? = refreshTokenRepository.findById(tokenId).orElseThrow().revokedAt

    companion object {
        private const val SEED_PASSWORD = "seed-password"
    }
}
