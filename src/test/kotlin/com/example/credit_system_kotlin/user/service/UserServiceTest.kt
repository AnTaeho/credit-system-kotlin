package com.example.credit_system_kotlin.user.service

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
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
        UserService(userRepository, UserFinder(userRepository), ledgerRepository)

    @Test
    fun `충전하면 잔액이 증가하고 ledger에 CHARGE가 남는다`() {
        val user = userRepository.save(User("acme", 500L))

        val response = userService.charge(user.persistedId, "idem-1", 300L)

        assertThat(response.balance).isEqualTo(800L)
        assertThat(response.duplicate).isFalse()
        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId))
            .anyMatch { it.type == LedgerType.CHARGE }
    }

    @Test
    fun `같은 idemKey로 두 번 충전해도 잔액은 한 번만 오른다`() {
        val user = userRepository.save(User("acme", 500L))
        val idemKey = "idem-dup"

        val first = userService.charge(user.persistedId, idemKey, 300L)
        val second = userService.charge(user.persistedId, idemKey, 300L)

        assertThat(first.duplicate).isFalse()
        assertThat(first.balance).isEqualTo(800L)
        assertThat(second.duplicate).isTrue()
        assertThat(second.balance).isEqualTo(800L)

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(found.balance).isEqualTo(800L)

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)
        assertThat(entries).filteredOn { it.type == LedgerType.CHARGE }.hasSize(1)
    }

    @Test
    fun `다른 idemKey면 각각 충전된다`() {
        val user = userRepository.save(User("acme", 500L))

        val first = userService.charge(user.persistedId, "idem-a", 300L)
        val second = userService.charge(user.persistedId, "idem-b", 200L)

        assertThat(first.duplicate).isFalse()
        assertThat(second.duplicate).isFalse()
        assertThat(second.balance).isEqualTo(1000L)

        val entries = ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)
        assertThat(entries).filteredOn { it.type == LedgerType.CHARGE }.hasSize(2)
    }

    /**
     * Java 원본에는 `charge(id, null, 300L)` 로 null을 넘기는 테스트가 하나 더 있었다.
     * Kotlin에서 idemKey를 non-null로 닫았으므로(report.md A-4) 그 호출은 컴파일되지 않는다.
     * 대신 필드가 없는 JSON이 400으로 거부되는지를
     * UserApiControllerTest 에서 확인한다.
     */
    @Test
    fun `idemKey가 공백이면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))

        assertThatThrownBy { userService.charge(user.persistedId, "   ", 300L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 필수입니다.")
    }

    @Test
    fun `idemKey가 100자를 초과하면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))
        val tooLong = "a".repeat(101)

        assertThatThrownBy { userService.charge(user.persistedId, tooLong, 300L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("idemKey는 100자를 초과할 수 없습니다.")
    }

    @Test
    fun `충전 금액이 0 이하면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))

        assertThatThrownBy { userService.charge(user.persistedId, "idem-1", 0L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("amount는 0보다 커야 합니다.")

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    @Test
    fun `충전 금액이 상한을 초과하면 거부한다`() {
        val user = userRepository.save(User("acme", 500L))

        assertThatThrownBy { userService.charge(user.persistedId, "idem-1", 1_000_001L) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("amount는 1,000,000을 초과할 수 없습니다.")

        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance)
            .isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    @Test
    fun `존재하지 않는 사용자를 충전하면 예외가 발생한다`() {
        val user = userRepository.save(User("acme", 500L))
        val missingUserId = user.persistedId + 999_999L

        assertThatThrownBy { userService.charge(missingUserId, "idem-1", 300L) }
            .isInstanceOf(UserNotFoundException::class.java)
            .hasMessage("존재하지 않는 user: $missingUserId")
    }
}
