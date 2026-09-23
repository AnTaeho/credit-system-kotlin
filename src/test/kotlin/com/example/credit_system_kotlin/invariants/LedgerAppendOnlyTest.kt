package com.example.credit_system_kotlin.invariants

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * INV-06 — `ledger_entries` 는 삽입만 되는 테이블이다(요구서 4-5, 사용자 확정 2026-09-23).
 *
 * 강제 수단은 V6 가 싣는 `BEFORE UPDATE`/`BEFORE DELETE` 트리거 + `SIGNAL` 이다.
 * 권한 REVOKE 로는 성립하지 않는다 — MySQL 권한에는 거부가 없고, 앱·Flyway·테스트가 모두
 * 같은 `credit` 계정이며 [SharedContainers] 셋업이 DB 단위 `GRANT ALL` 을 준다
 * (`git show req-v3:docs/02-design.md` 2절 gap 표 INV-06 행).
 *
 * H2 를 쓰는 테스트는 Hibernate 가 스키마를 만들어 마이그레이션을 거치지 않으므로,
 * 이 불변식은 실제 MySQL(Testcontainers) 위에서만 증명된다.
 */
@ActiveProfiles("test")
@SpringBootTest
class LedgerAppendOnlyTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "ledger_append_only")
        }

        private const val APPEND_ONLY_MESSAGE = "append-only"
    }

    private fun newEntry(name: String): Long {
        val user = userRepository.save(User(name, 0L))
        val entry = ledgerRepository.saveAndFlush(LedgerEntry.adminGrant(user.persistedId, "$name-idem", 300L))
        return entry.persistedId
    }

    /**
     * 트리거 생성은 서버 플래그를 전제한다. 재사용 컨테이너가 옛 command 로 떠 있으면 V6 가
     * ERROR 1419 로 실패하는데, 그 원인을 여기서 먼저 드러낸다.
     */
    @Test
    fun `컨테이너에 log_bin_trust_function_creators 가 켜져 있다`() {
        val value = jdbcTemplate.queryForObject(
            "SELECT @@GLOBAL.log_bin_trust_function_creators",
            String::class.java
        )

        assertThat(value)
            .describedAs("log_bin_trust_function_creators (0 이면 credit 계정이 CREATE TRIGGER 를 못 한다)")
            .isEqualTo("1")
    }

    @Test
    fun `원장 행은 UPDATE 되지 않는다`() {
        val id = newEntry("append-only-update")

        assertThatThrownBy {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", id)
        }.hasMessageContaining(APPEND_ONLY_MESSAGE)

        val amount = jdbcTemplate.queryForObject(
            "SELECT amount FROM ledger_entries WHERE id = ?", Long::class.java, id
        )
        assertThat(amount).describedAs("막힌 UPDATE 뒤의 금액").isEqualTo(300L)
    }

    @Test
    fun `원장 행은 DELETE 되지 않는다`() {
        val id = newEntry("append-only-delete")

        assertThatThrownBy {
            jdbcTemplate.update("DELETE FROM ledger_entries WHERE id = ?", id)
        }.hasMessageContaining(APPEND_ONLY_MESSAGE)

        val remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE id = ?", Long::class.java, id
        )
        assertThat(remaining).describedAs("막힌 DELETE 뒤의 행 수").isEqualTo(1L)
    }

    /**
     * 막는 것은 UPDATE·DELETE 뿐이다. INSERT 가 함께 막히면 서비스도 대용량 시드도 멈춘다.
     */
    @Test
    fun `INSERT 는 그대로 된다`() {
        val id = newEntry("append-only-insert")

        assertThat(id).isPositive()
        assertThat(ledgerRepository.findById(id)).isPresent()
    }
}
