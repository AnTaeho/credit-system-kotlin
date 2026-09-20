package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.domain.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * V5 가 실제 MySQL 에서 돌고, 그 결과 스키마가 엔티티 매핑과 맞는지 본다.
 *
 * 컨텍스트가 뜬 것 자체가 절반의 단언이다 — `ddl-auto: validate` 라서 컬럼 타입이 어긋나면
 * 기동에서 죽는다. 나머지 절반은 디스패처 조회 조건이 진짜 MySQL 에서 의도대로 걸러지는지다.
 * H2 는 마이그레이션을 타지 않으므로 이걸 확인할 자리는 여기뿐이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class JobsNextAttemptAtMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val jobRepository: JobRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "jobs_next_attempt_at_migration")
        }
    }

    @Test
    fun `V5 뒤 jobs 에는 null 가능한 next_attempt_at datetime(6) 이 있다`() {
        val column = jdbcTemplate.queryForMap(
            """
            SELECT COLUMN_TYPE, IS_NULLABLE, DATETIME_PRECISION FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jobs' AND COLUMN_NAME = 'next_attempt_at'
            """
        )

        assertThat(column["COLUMN_TYPE"].toString()).isEqualTo("datetime(6)")
        assertThat(column["IS_NULLABLE"]).isEqualTo("YES")
        assertThat((column["DATETIME_PRECISION"] as Number).toInt()).isEqualTo(6)
    }

    @Test
    fun `실제 MySQL 에서도 대기 시각 전의 job 은 집히지 않는다`() {
        val now = Instant.parse("2026-09-20T00:00:00Z")
        val waiting = jobRepository.save(Job.hold(1L, 100L, "cat"))
        jobRepository.transitionIfStatusAndAttemptMatch(
            waiting.persistedId, JobStatus.FAILED, JobStatus.HOLDING, 0, now
        )
        jobRepository.incrementAttemptForRetry(waiting.persistedId, 0, now.plusSeconds(10), now)
        val fresh = jobRepository.save(Job.hold(1L, 100L, "dog"))

        val beforeDue = jobRepository.findDispatchableByStatus(JobStatus.HOLDING, now, PageRequest.of(0, 10))
        val atDue = jobRepository.findDispatchableByStatus(
            JobStatus.HOLDING, now.plusSeconds(10), PageRequest.of(0, 10)
        )

        // 최초 접수(next_attempt_at IS NULL)는 두 시점 모두에서 집힌다.
        assertThat(beforeDue).extracting<Long> { it.persistedId }
            .contains(fresh.persistedId)
            .doesNotContain(waiting.persistedId)
        assertThat(atDue).extracting<Long> { it.persistedId }
            .contains(fresh.persistedId, waiting.persistedId)
    }
}
