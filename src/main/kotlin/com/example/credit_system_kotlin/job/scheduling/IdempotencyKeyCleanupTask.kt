package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger(IdempotencyKeyCleanupTask::class.java)

@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdempotencyKeyCleanupTask(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val idempotencyProperties: IdempotencyProperties
) {

    @Scheduled(fixedDelayString = $$"${app.scheduling.idempotency-cleanup-interval-millis:3600000}")
    fun cleanup() {
        val cutoff = Instant.now().minus(idempotencyProperties.retentionDays, ChronoUnit.DAYS)
        var deletedCount = 0
        do {
            val ids = idempotencyKeyRepository.findIdsCreatedBefore(cutoff, PageRequest.of(0, CLEANUP_BATCH_SIZE))
            if (ids.isEmpty()) {
                break
            }
            try {
                deletedCount += idempotencyKeyRepository.deleteByIdIn(ids)
            } catch (e: RuntimeException) {
                log.warn("멱등키 정리 배치 삭제 실패, 이번 주기 중단", e)
                break
            }
        } while (ids.size == CLEANUP_BATCH_SIZE)

        if (deletedCount > 0) {
            log.info("멱등키 정리 주기 완료: deletedCount={}", deletedCount)
        } else {
            log.debug("멱등키 정리 주기 완료: 삭제 대상 없음")
        }
    }

    companion object {
        private const val CLEANUP_BATCH_SIZE = 500
    }
}
