package com.example.credit_system_kotlin.ledger.repository

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
 * baseline이 실제 MySQL 에서 원장 유형 enum 에 ADMIN_GRANT 를 포함하는지 확인한다.
 * `ddl-auto: validate` 는 enum 값의 나열 순서를 보지 않아 `information_schema` 로 직접 단언한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class LedgerTypeMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "ledger_type_migration")
        }
    }

    @Test
    fun `baseline 적용 후 원장 유형 컬럼은 ADMIN_GRANT 를 포함한 알파벳 순 네이티브 enum 이다`() {
        val columnType = jdbcTemplate.queryForObject(
            """
            SELECT COLUMN_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ledger_entries' AND COLUMN_NAME = 'type'
            """,
            String::class.java
        )

        assertThat(columnType).isEqualTo("enum('ADMIN_GRANT','CHARGE','CONFIRM','HOLD','REFUND')")
    }
}
