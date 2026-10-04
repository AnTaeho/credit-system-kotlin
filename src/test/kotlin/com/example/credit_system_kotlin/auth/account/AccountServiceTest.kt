package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.global.exception.EmailAlreadyUsedException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class AccountServiceTest @Autowired constructor(
    private val userRepository: UserRepository
) {

    // 운영 기본 강도(10)는 테스트마다 수백 ms 가 든다. 동작은 같으므로 가장 낮은 강도로 돌린다.
    private val passwordEncoder = BCryptPasswordEncoder(4)

    private val accountService = AccountService(userRepository, passwordEncoder)

    @Test
    fun `가입하면 잔액 0 역할 USER 로 만들어지고 비밀번호는 해시로만 남는다`() {
        val user = accountService.signUp("  Alice@Example.com ", PASSWORD)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.email).isEqualTo("alice@example.com")
        assertThat(found.name).isEqualTo("alice")
        assertThat(found.balance).isZero()
        assertThat(found.initialBalance).isZero()
        assertThat(found.role).isEqualTo(UserRole.USER)
        assertThat(found.googleSub).isNull()
        assertThat(found.passwordHash).isNotEqualTo(PASSWORD).hasSize(60)
        assertThat(passwordEncoder.matches(PASSWORD, found.passwordHash)).isTrue()
    }

    @Test
    fun `대소문자만 다른 이메일은 이미 가입된 이메일이다`() {
        accountService.signUp("alice@example.com", PASSWORD)

        assertThatThrownBy { accountService.signUp("ALICE@Example.COM", "another-password") }
            .isInstanceOf(EmailAlreadyUsedException::class.java)
            .hasMessage("이미 가입된 이메일입니다.")
        assertThat(userRepository.count()).isEqualTo(1)
    }

    @Test
    fun `비밀번호 없이 만들어진 옛 행의 이메일로도 다시 가입할 수 없다`() {
        userRepository.save(User("legacy", 0L, email = "legacy@example.com", googleSub = "sub-1"))

        assertThatThrownBy { accountService.signUp("legacy@example.com", PASSWORD) }
            .isInstanceOf(EmailAlreadyUsedException::class.java)
    }

    @Test
    fun `형식이 틀리거나 너무 긴 이메일은 거절한다`() {
        listOf("", "   ", "no-at-sign", "@example.com", "alice@", "alice@nodot", "a b@example.com").forEach {
            assertThatThrownBy { accountService.signUp(it, PASSWORD) }
                .`as`("'$it'")
                .isInstanceOf(InvalidRequestException::class.java)
        }
        assertThatThrownBy { accountService.signUp("a".repeat(244) + "@example.com", PASSWORD) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("이메일은 255자를 초과할 수 없습니다.")
        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `비밀번호가 7자면 거절하고 8자면 받는다`() {
        assertThatThrownBy { accountService.signUp("alice@example.com", "1234567") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("비밀번호는 8자 이상이어야 합니다.")

        assertThat(accountService.signUp("alice@example.com", "12345678").id).isNotNull()
    }

    @Test
    fun `비밀번호가 UTF-8 로 73바이트면 거절하고 72바이트면 받는다`() {
        assertThatThrownBy { accountService.signUp("alice@example.com", "a".repeat(73)) }
            .isInstanceOf(InvalidRequestException::class.java)
        // 글자 수는 25자지만 한글은 3바이트씩이라 75바이트다.
        assertThatThrownBy { accountService.signUp("alice@example.com", "가".repeat(25)) }
            .isInstanceOf(InvalidRequestException::class.java)

        assertThat(accountService.signUp("alice@example.com", "a".repeat(72)).id).isNotNull()
    }

    @Test
    fun `이메일과 비밀번호가 맞으면 그 사용자를 돌려준다`() {
        val user = accountService.signUp("alice@example.com", PASSWORD)

        assertThat(accountService.authenticate(" ALICE@example.com", PASSWORD)?.id).isEqualTo(user.persistedId)
    }

    @Test
    fun `틀린 비밀번호와 없는 이메일과 해시 없는 옛 행은 모두 null 이다`() {
        accountService.signUp("alice@example.com", PASSWORD)
        userRepository.save(User("legacy", 0L, email = "legacy@example.com", googleSub = "sub-1"))

        assertThat(accountService.authenticate("alice@example.com", "wrong-password")).`as`("틀린 비밀번호").isNull()
        assertThat(accountService.authenticate("nobody@example.com", PASSWORD)).`as`("없는 이메일").isNull()
        assertThat(accountService.authenticate("legacy@example.com", PASSWORD)).`as`("해시 없는 옛 행").isNull()
        assertThat(accountService.authenticate("alice@example.com", "a".repeat(100))).`as`("72바이트 초과").isNull()
    }

    companion object {
        private const val PASSWORD = "correct-horse-battery"
    }
}
