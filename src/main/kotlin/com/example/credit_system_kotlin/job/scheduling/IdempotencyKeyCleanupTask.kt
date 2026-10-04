package com.example.credit_system_kotlin.job.scheduling

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger(IdempotencyKeyCleanupTask::class.java)

/**
 * 보존 기간(`app.idempotency.retention-days`, 기본 7일)이 지난 멱등키를 지운다.
 *
 * 키가 지워진 뒤 같은 키로 다시 보내면 새 요청으로 처리된다(새로 차감한다). 보존 기간은
 * 클라이언트 재시도 창을 덮으면 충분하다고 보고 이 동작을 받아들인다.
 */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdempotencyKeyCleanupTask(
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val appProperties: AppProperties
) {

    // 보존 기간이 7일이라 하루 한 번이면 충분하다. 트래픽이 한산한 시각에 돌려 삭제 락이
    // 멱등키 INSERT 와 부딪힐 여지를 줄인다.
    @Scheduled(
        cron = $$"${app.scheduling.idempotency-cleanup-cron:0 0 2 * * *}",
        zone = $$"${app.scheduling.timezone:Asia/Seoul}"
    )
    fun cleanup() {
        val cutoff = Instant.now().minus(appProperties.idempotency.retentionDays, ChronoUnit.DAYS)
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
