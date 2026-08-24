package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.global.config.IdempotencyProperties
import com.example.credit_system_kotlin.job.domain.IdempotencyKey
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.temporal.ChronoUnit

@ActiveProfiles("test")
@DataJpaTest
class IdempotencyKeyCleanupTaskTest @Autowired constructor(
    private val idempotencyKeyRepository: IdempotencyKeyRepository
) {

    private val task = IdempotencyKeyCleanupTask(idempotencyKeyRepository, IdempotencyProperties(7))

    private fun saveExpired(idemKey: String): IdempotencyKey {
        val key = idempotencyKeyRepository.save(IdempotencyKey(1L, idemKey))
        ReflectionTestUtils.setField(key, "createdAt", Instant.now().minus(8, ChronoUnit.DAYS))
        return idempotencyKeyRepository.save(key)
    }

    @Test
    fun `보존 기간이 지난 키는 삭제된다`() {
        val key = saveExpired("old-key")

        task.cleanup()

        assertThat(idempotencyKeyRepository.findById(key.persistedId)).isEmpty()
    }

    @Test
    fun `보존 기간 안의 키는 남는다`() {
        val key = idempotencyKeyRepository.save(IdempotencyKey(1L, "recent-key"))

        task.cleanup()

        assertThat(idempotencyKeyRepository.findById(key.persistedId)).isPresent()
    }

    @Test
    fun `배치 크기를 넘는 키도 모두 삭제된다`() {
        repeat(510) { saveExpired("key-$it") }

        task.cleanup()

        assertThat(idempotencyKeyRepository.count()).isZero()
    }

    @Test
    fun `지울 키가 없으면 아무것도 지우지 않는다`() {
        task.cleanup()

        assertThat(idempotencyKeyRepository.count()).isZero()
    }
}
