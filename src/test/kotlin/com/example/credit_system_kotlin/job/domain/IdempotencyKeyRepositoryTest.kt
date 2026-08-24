package com.example.credit_system_kotlin.job.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
class IdempotencyKeyRepositoryTest @Autowired constructor(
    private val idempotencyKeyRepository: IdempotencyKeyRepository
) {

    @Test
    fun `동일 조직 동일 키는 유니크 제약으로 거부된다`() {
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey(1L, "key-1"))

        assertThatThrownBy { idempotencyKeyRepository.saveAndFlush(IdempotencyKey(1L, "key-1")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `다른 조직은 같은 키를 사용할 수 있다`() {
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey(1L, "key-1"))
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey(2L, "key-1"))

        assertThat(idempotencyKeyRepository.count()).isEqualTo(2)
    }

    @Test
    fun `attachJobId로 job을 연결할 수 있다`() {
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey(1L, "key-1"))

        val updated = idempotencyKeyRepository.attachJobId(1L, "key-1", 42L)

        val found = requireNotNull(idempotencyKeyRepository.findByOrganizationIdAndIdemKey(1L, "key-1"))
        assertThat(updated).isEqualTo(1)
        assertThat(found.jobId).isEqualTo(42L)
    }
}
