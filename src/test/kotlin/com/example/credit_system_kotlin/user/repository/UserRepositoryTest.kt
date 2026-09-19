package com.example.credit_system_kotlin.user.repository

import com.example.credit_system_kotlin.user.domain.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@DataJpaTest
class UserRepositoryTest @Autowired constructor(
    private val userRepository: UserRepository
) {

    @Test
    fun `잔액이 충분하면 차감된다`() {
        val user = userRepository.save(User("acme", 1000L))

        val updated = userRepository.deductBalance(user.persistedId, 300L, Instant.now())
        userRepository.flush()

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(updated).isEqualTo(1)
        assertThat(found.balance).isEqualTo(700L)
    }

    @Test
    fun `잔액이 부족하면 0행이 반환되고 잔액이 변하지 않는다`() {
        val user = userRepository.save(User("acme", 100L))

        val updated = userRepository.deductBalance(user.persistedId, 300L, Instant.now())

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(updated).isZero()
        assertThat(found.balance).isEqualTo(100L)
    }

    @Test
    fun `환불은 잔액을 되돌린다`() {
        val user = userRepository.save(User("acme", 700L))

        val updated = userRepository.addBalance(user.persistedId, 300L, Instant.now())

        val found = userRepository.findById(user.persistedId).orElseThrow()
        assertThat(updated).isEqualTo(1)
        assertThat(found.balance).isEqualTo(1000L)
    }
}
