package com.example.credit_system_kotlin.job.repository

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * V4 가 실제 MySQL 에 사용자별 job 커서 페이징용 인덱스를 (user_id, id) 순서로 만드는지 확인한다.
 *
 * `ddl-auto: validate` 는 인덱스를 검사하지 않으므로 `information_schema` 로 직접 단언한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class JobsUserIdIndexMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "jobs_user_id_index_migration")
        }
    }

    @Test
    fun `V4 뒤 jobs 에는 user_id, id 순서의 idx_jobs_user_id 가 있다`() {
        val columns = jdbcTemplate.queryForList(
            """
            SELECT COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jobs' AND INDEX_NAME = 'idx_jobs_user_id'
            ORDER BY SEQ_IN_INDEX
            """,
            String::class.java
        )

        assertThat(columns).containsExactly("user_id", "id")
    }
}
