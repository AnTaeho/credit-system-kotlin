package com.example.credit_system_kotlin.auth.token

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
 * 마이그레이션이 실제 MySQL에 현재 사용자 스키마를 만들고, V3 뒤에는 리프레시 토큰 테이블이 없는지 확인한다.
 * `ddl-auto: validate` 가 보지 않는 enum 값의 나열, 기본값, 유니크 키는 `information_schema` 로 직접 단언한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class AuthTokenMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "auth_token_migration")
        }
    }

    @Test
    fun `baseline 적용 후 users 의 role 은 기본 USER 인 네이티브 enum 이다`() {
        val role = jdbcTemplate.queryForMap(
            """
            SELECT COLUMN_TYPE, COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
            """
        )
        assertThat(role["COLUMN_TYPE"].toString()).isEqualTo("enum('ADMIN','USER')")
        assertThat(role["COLUMN_DEFAULT"].toString()).isEqualTo("USER")
        assertThat(role["IS_NULLABLE"].toString()).isEqualTo("NO")
    }

    @Test
    fun `baseline 적용 후 users 에는 google_sub 컬럼과 그 유니크 키가 없고 이메일 유니크 키는 남아 있다`() {
        val googleSubColumns = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'google_sub'
            """,
            Int::class.java
        )
        assertThat(googleSubColumns).isZero()

        val uniqueKeys = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND NON_UNIQUE = 0
            """,
            String::class.java
        )
        assertThat(uniqueKeys).containsExactlyInAnyOrder("PRIMARY", "uk_users_email")
    }

    // 리프레시 토큰은 Redis 로 옮겼다. V1 이 만든 테이블을 V3 가 지운다.
    @Test
    fun `V3 적용 후 refresh_tokens 테이블은 없고 users 테이블은 남아 있다`() {
        val tables = jdbcTemplate.queryForList(
            """
            SELECT TABLE_NAME FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('refresh_tokens', 'users')
            """,
            String::class.java
        )
        assertThat(tables).containsExactly("users")

        val applied = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
            String::class.java
        )
        assertThat(applied).contains("3")
    }
}
