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
 * V7(users 의 password_hash·role), V8(refresh_tokens), V9(users 의 google_sub 삭제)가 실제 MySQL 에서 도는지 확인한다.
 *
 * 컨텍스트가 뜨는 것 자체가 `ddl-auto: validate` 통과, 곧 컬럼 타입·폭이 엔티티 매핑과 같다는 증거다.
 * validate 가 보지 않는 것(enum 값의 나열, 기본값, 유니크 키)은 `information_schema` 로 직접 단언한다.
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
}
