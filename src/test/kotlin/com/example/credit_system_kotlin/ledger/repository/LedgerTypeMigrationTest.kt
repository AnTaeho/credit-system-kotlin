package com.example.credit_system_kotlin.ledger.repository

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * V3 가 실제 MySQL 에서 원장 유형 enum 에 ADMIN_GRANT 를 더하는지 확인한다.
 *
 * H2 는 Hibernate 가 스키마를 만들어 이 마이그레이션을 거치지 않는다. 그리고 `ddl-auto: validate` 통과만으로는
 * enum 값의 나열 순서까지 보장되지 않으므로, V1 이 받아 적은 규약(Hibernate 생성물과 같은 알파벳 순)을
 * `information_schema` 로 직접 단언한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class LedgerTypeMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "ledger_type_migration")
        }
    }

    @Test
    fun `V3 뒤 원장 유형 컬럼은 ADMIN_GRANT 를 포함한 알파벳 순 네이티브 enum 이다`() {
        val columnType = jdbcTemplate.queryForObject(
            """
            SELECT COLUMN_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ledger_entries' AND COLUMN_NAME = 'type'
            """,
            String::class.java
        )

        assertThat(columnType).isEqualTo("enum('ADMIN_GRANT','CHARGE','CONFIRM','HOLD','REFUND')")
    }

    @Test
    fun `ADMIN_GRANT 행이 네이티브 enum 에 저장되고 그대로 읽힌다`() {
        val user = userRepository.save(User("grant-target", 0L))

        ledgerRepository.saveAndFlush(LedgerEntry.adminGrant(user.persistedId, "grant-enum-1", 300L))

        val stored = jdbcTemplate.queryForObject(
            "SELECT type FROM ledger_entries WHERE user_id = ? AND idem_key = ?",
            String::class.java, user.persistedId, "grant-enum-1"
        )
        assertThat(stored).isEqualTo("ADMIN_GRANT")
        val read = requireNotNull(ledgerRepository.findByUserIdAndIdemKey(user.persistedId, "grant-enum-1"))
        assertThat(read.type).isEqualTo(LedgerType.ADMIN_GRANT)
        assertThat(read.amount).isEqualTo(300L)
    }
}
