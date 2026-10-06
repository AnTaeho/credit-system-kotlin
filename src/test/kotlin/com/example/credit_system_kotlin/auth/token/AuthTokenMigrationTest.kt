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
 * V7(users 의 password_hash·role), V8(refresh_tokens), V9(users 의 google_sub 삭제), V10(refresh_tokens 의
 * family_id·rotated_at·revoked_at 삭제)이 실제 MySQL 에서 도는지 확인한다.
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
    fun `V7 V8 뒤 users 의 role 은 기본 USER 인 네이티브 enum 이고 refresh_tokens 의 token_hash 는 유니크다`() {
        val role = jdbcTemplate.queryForMap(
            """
            SELECT COLUMN_TYPE, COLUMN_DEFAULT, IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'role'
            """
        )
        assertThat(role["COLUMN_TYPE"].toString()).isEqualTo("enum('ADMIN','USER')")
        assertThat(role["COLUMN_DEFAULT"].toString()).isEqualTo("USER")
        assertThat(role["IS_NULLABLE"].toString()).isEqualTo("NO")

        val uniqueOnTokenHash = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_tokens'
              AND COLUMN_NAME = 'token_hash' AND NON_UNIQUE = 0
            """,
            Int::class.java
        )
        assertThat(uniqueOnTokenHash).isEqualTo(1)
    }

    @Test
    fun `V9 뒤 users 에는 google_sub 컬럼과 그 유니크 키가 없고 이메일 유니크 키는 남아 있다`() {
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

    @Test
    fun `V10 뒤 refresh_tokens 에는 family_id rotated_at revoked_at 컬럼과 family_id 인덱스가 없다`() {
        val columns = jdbcTemplate.queryForList(
            """
            SELECT COLUMN_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_tokens'
            """,
            String::class.java
        )
        assertThat(columns)
            .doesNotContain("family_id", "rotated_at", "revoked_at")
            .containsExactlyInAnyOrder("id", "user_id", "token_hash", "expires_at", "created_at", "updated_at")

        val indexes = jdbcTemplate.queryForList(
            """
            SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_tokens'
            """,
            String::class.java
        )
        assertThat(indexes)
            .doesNotContain("idx_refresh_tokens_family_id")
            .containsExactlyInAnyOrder(
                "PRIMARY",
                "uk_refresh_tokens_token_hash",
                "idx_refresh_tokens_user_id",
                "idx_refresh_tokens_expires_at"
            )
    }
}
