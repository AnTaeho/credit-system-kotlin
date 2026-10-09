package com.example.credit_system_kotlin.integration.user.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.appProperties
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.dto.GrantRequest
import com.example.credit_system_kotlin.user.repository.UserRepository
import com.example.credit_system_kotlin.user.service.UserFinder
import com.example.credit_system_kotlin.user.service.UserService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class UserServiceTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    private val userService =
        UserService(userRepository, UserFinder(userRepository), ledgerRepository, appProperties())

    @Test
    fun `지급하면 잔액이 증가하고 ledger에 ADMIN_GRANT가 양수로 남는다`() {
        val user = userRepository.save(User("acme", 500L))

        val response = userService.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-1", 300L))

        assertThat(response.balance).isEqualTo(800L)
        assertThat(response.duplicate).isFalse()
        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .singleElement()
            .satisfies({
                assertThat(it.type).isEqualTo(LedgerType.ADMIN_GRANT)
                assertThat(it.amount).isEqualTo(300L)
            })
    }

    @Test
    fun `같은 idemKey로 두 번 지급해도 잔액은 한 번만 오른다`() {
        val user = userRepository.save(User("acme", 500L))
        val idemKey = "idem-dup"

        val first = userService.grant(ADMIN_ID, user.persistedId, GrantRequest(idemKey, 300L))
        val second = userService.grant(ADMIN_ID, user.persistedId, GrantRequest(idemKey, 300L))

        assertThat(first.duplicate).isFalse()
        assertThat(first.balance).isEqualTo(800L)
        assertThat(second.duplicate).isTrue()
        assertThat(second.balance).isEqualTo(800L)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)
        assertThat(entries).filteredOn { it.type == LedgerType.ADMIN_GRANT }.hasSize(1)
    }

    @Test
    fun `다른 idemKey면 각각 지급된다`() {
        val user = userRepository.save(User("acme", 500L))

        val first = userService.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-a", 300L))
        val second = userService.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-b", 200L))

        assertThat(first.duplicate).isFalse()
        assertThat(second.duplicate).isFalse()
        assertThat(second.balance).isEqualTo(1000L)

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)
        assertThat(entries).filteredOn { it.type == LedgerType.ADMIN_GRANT }.hasSize(2)
    }

    // idemKey 가 non-null 이라 null 을 넘기는 테스트는 없다.
    @Test
    fun `idemKey가 공백이면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))

        assertThatThrownBy { userService.grant(ADMIN_ID, user.persistedId, GrantRequest("   ", 300L)) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 필수입니다.")
    }

    @Test
    fun `idemKey가 100자를 초과하면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))
        val tooLong = "a".repeat(101)

        assertThatThrownBy { userService.grant(ADMIN_ID, user.persistedId, GrantRequest(tooLong, 300L)) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 100자를 초과할 수 없습니다.")
    }

    @Test
    fun `지급 금액이 0 이하면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))

        listOf(0L, -1L).forEach { amount ->
            assertThatThrownBy { userService.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-1", amount)) }
                .`as`("$amount")
                .isInstanceOf(InvalidRequestException::class.java)
                .hasMessage("amount는 0보다 커야 합니다.")
        }

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    @Test
    fun `존재하지 않는 사용자에게 지급하면 예외가 발생한다`() {
        val user = userRepository.save(User("acme", 500L))
        val missingUserId = user.persistedId + 999_999L

        assertThatThrownBy { userService.grant(ADMIN_ID, missingUserId, GrantRequest("idem-1", 300L)) }
            .isInstanceOf(UserNotFoundException::class.java)
            .hasMessage("존재하지 않는 user: $missingUserId")
    }

    @Test
    fun `지급 상한은 설정값을 따른다`() {
        val user = userRepository.save(User("acme", 500L))
        val limited = UserService(
            userRepository, UserFinder(userRepository), ledgerRepository,
            appProperties(admin = AppProperties.Admin(maxGrantAmount = 1_000L))
        )

        assertThatThrownBy { limited.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-1", 1_001L)) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("amount는 1,000을 초과할 수 없습니다.")
        assertThat(limited.grant(ADMIN_ID, user.persistedId, GrantRequest("idem-2", 1_000L)).balance).isEqualTo(1_500L)
    }

    companion object {
        /** 지급한 운영자. 서비스는 로그에만 남기므로 실제 행이 없어도 된다. */
        private const val ADMIN_ID = 1L
    }
}
