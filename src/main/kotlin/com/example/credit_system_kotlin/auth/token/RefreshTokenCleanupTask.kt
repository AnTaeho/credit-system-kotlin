package com.example.credit_system_kotlin.auth.token

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

private val log = LoggerFactory.getLogger(RefreshTokenCleanupTask::class.java)

/** 만료된 리프레시 토큰을 지운다. 만료된 행은 이미 거절되므로 지워도 동작은 같고 테이블만 줄어든다. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RefreshTokenCleanupTask(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock
) {

    // 멱등키 정리(02:00)와 겹치지 않게 한 시간 뒤에 돌린다.
    @Scheduled(
        cron = $$"${app.scheduling.refresh-token-cleanup-cron:0 0 3 * * *}",
        zone = $$"${app.scheduling.timezone:Asia/Seoul}"
    )
    fun cleanup() {
        val cutoff = clock.instant()
        var deletedCount = 0
        do {
            val ids = refreshTokenRepository.findIdsExpiredBefore(cutoff, PageRequest.of(0, CLEANUP_BATCH_SIZE))
            if (ids.isEmpty()) {
                break
            }
            try {
                deletedCount += refreshTokenRepository.deleteByIdIn(ids)
            } catch (e: RuntimeException) {
                log.warn("리프레시 토큰 정리 배치 삭제 실패, 이번 주기 중단", e)
                break
            }
        } while (ids.size == CLEANUP_BATCH_SIZE)

        if (deletedCount > 0) {
            log.info("리프레시 토큰 정리 주기 완료: deletedCount={}", deletedCount)
        } else {
            log.debug("리프레시 토큰 정리 주기 완료: 삭제 대상 없음")
        }
    }

    companion object {
        private const val CLEANUP_BATCH_SIZE = 500
    }
}
