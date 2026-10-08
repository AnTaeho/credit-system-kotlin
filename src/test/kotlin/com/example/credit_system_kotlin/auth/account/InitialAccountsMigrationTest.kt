package com.example.credit_system_kotlin.auth.account

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import javax.sql.DataSource

// V2 가 실제 MySQL 에 로그인 계정 둘을 넣는지, 적어 둔 해시가 공개된 비밀번호와 맞는지 확인한다.
@ActiveProfiles("test")
@SpringBootTest
class InitialAccountsMigrationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
    private val passwordEncoder: PasswordEncoder
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "initial_accounts_migration")
        }

        private const val SELECT_USERS =
            "SELECT id, name, email, role, balance, initial_balance, password_hash FROM users ORDER BY id"
    }

    @Test
    fun `V2 적용 후 users 에는 id 1 인 dev 사용자와 id 2 인 admin 운영자가 있고 해시는 공개된 비밀번호와 맞는다`() {
        val rows = jdbcTemplate.queryForList(SELECT_USERS)

        assertThat(rows).hasSize(2)
        assertAccount(rows[0], 1L, "dev", "dev@local.test", "USER", "local-dev-password")
        assertAccount(rows[1], 2L, "admin", "admin@local.test", "ADMIN", "local-admin-password")
    }

    @Test
    fun `V2 를 같은 DB 에 다시 실행해도 행이 늘거나 바뀌지 않는다`() {
        val before = jdbcTemplate.queryForList(SELECT_USERS)

        dataSource.connection.use { connection ->
            ScriptUtils.executeSqlScript(connection, ClassPathResource("db/migration/V2__seed_accounts.sql"))
        }

        assertThat(jdbcTemplate.queryForList(SELECT_USERS)).isEqualTo(before).hasSize(2)
    }

    private fun assertAccount(
        row: Map<String, Any?>,
        id: Long,
        name: String,
        email: String,
        role: String,
        rawPassword: String
    ) {
        assertThat((row["id"] as Number).toLong()).isEqualTo(id)
        assertThat(row["name"]).isEqualTo(name)
        assertThat(row["email"]).isEqualTo(email)
        assertThat(row["role"]).isEqualTo(role)
        assertThat((row["balance"] as Number).toLong()).isZero()
        assertThat((row["initial_balance"] as Number).toLong()).isZero()
        val hash = row["password_hash"] as String
        assertThat(hash).hasSize(60)
        assertThat(passwordEncoder.matches(rawPassword, hash)).isTrue()
    }
}
