package com.example.credit_system_kotlin.benchmark

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

@Tag("benchmark")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Import(BalanceStrategyBenchmark.BenchmarkTestConfig::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BalanceStrategyBenchmark @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate,
    private val conditionalUpdateStrategy: ConditionalUpdateStrategy,
    private val pessimisticLockStrategy: PessimisticLockStrategy,
    private val optimisticLockStrategy: OptimisticLockStrategy
) {

    @TestConfiguration
    class BenchmarkTestConfig {

        @Bean
        fun benchTransactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(transactionManager)

        @Bean
        fun benchRequiresNewTransactionTemplate(
            transactionManager: PlatformTransactionManager
        ): TransactionTemplate = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        @Bean
        fun conditionalUpdateStrategy(
            jdbcTemplate: JdbcTemplate,
            benchTransactionTemplate: TransactionTemplate
        ) = ConditionalUpdateStrategy(jdbcTemplate, benchTransactionTemplate)

        @Bean
        fun pessimisticLockStrategy(
            jdbcTemplate: JdbcTemplate,
            benchTransactionTemplate: TransactionTemplate
        ) = PessimisticLockStrategy(jdbcTemplate, benchTransactionTemplate)

        @Bean
        fun optimisticLockStrategy(
            jdbcTemplate: JdbcTemplate,
            benchRequiresNewTransactionTemplate: TransactionTemplate
        ) = OptimisticLockStrategy(jdbcTemplate, benchRequiresNewTransactionTemplate)
    }

    @Test
    @Order(1)
    fun runFullBenchmarkMatrix() {
        val requests = Integer.getInteger("bench.requests", DEFAULT_REQUESTS)
        val warmup = Integer.getInteger("bench.warmup", DEFAULT_WARMUP)
        val concurrencyLevels = parseConcurrency(System.getProperty("bench.concurrency"))

        val initialBalance = requests.toLong() * AMOUNT * 2

        val strategies: List<DeductStrategy> = listOf(
            conditionalUpdateStrategy,
            pessimisticLockStrategy,
            optimisticLockStrategy
        )

        val results = ArrayList<BenchmarkResult>()

        for (concurrency in concurrencyLevels) {
            for (strategy in strategies) {
                resetBalance(initialBalance)
                BenchmarkHarness.run(strategy, concurrency, warmup, ACCOUNT_ID, AMOUNT)

                resetBalance(initialBalance)
                val result = BenchmarkHarness.run(strategy, concurrency, requests, ACCOUNT_ID, AMOUNT)
                results.add(result)

                verifyConsistency(result, initialBalance, requests)
            }
        }

        printMarkdownTable(results)
    }

    private fun resetBalance(initialBalance: Long) {
        val updated = jdbcTemplate.update(
            "UPDATE bench_account SET balance = ?, version = 0 WHERE id = ?",
            initialBalance, ACCOUNT_ID
        )
        if (updated == 0) {
            jdbcTemplate.update(
                "INSERT INTO bench_account (id, balance, version) VALUES (?, ?, 0)",
                ACCOUNT_ID, initialBalance
            )
        }
    }

    private fun verifyConsistency(result: BenchmarkResult, initialBalance: Long, requests: Int) {
        val finalBalance = jdbcTemplate.queryForObject(
            "SELECT balance FROM bench_account WHERE id = ?", Long::class.javaObjectType, ACCOUNT_ID
        )
        val expectedBalance = initialBalance - result.successCount.toLong() * AMOUNT

        assertThat(finalBalance)
            .`as`(
                "[%s @ concurrency=%d] final balance must equal initial - success*cost",
                result.strategy, result.concurrency
            )
            .isEqualTo(expectedBalance)

        assertThat(result.successCount + result.failureCount)
            .`as`(
                "[%s @ concurrency=%d] success + failure must equal total requests",
                result.strategy, result.concurrency
            )
            .isEqualTo(requests)
    }

    companion object {
        private const val ACCOUNT_ID = 1L
        private const val AMOUNT = 100L

        private const val DEFAULT_REQUESTS = 5000
        private const val DEFAULT_WARMUP = 500
        private val DEFAULT_CONCURRENCY = intArrayOf(1, 10, 50, 100)

        @Container
        @JvmStatic
        val mysql: MySQLContainer = MySQLContainer("mysql:8.4")
            .withDatabaseName("credit_system")
            .withUsername("credit")
            .withPassword("credit")
            .withCommand("--max-connections=200")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.datasource.driver-class-name") { mysql.driverClassName }
            registry.add("spring.datasource.hikari.maximum-pool-size") { 128 }
        }

        @JvmStatic
        @BeforeAll
        fun createTable(@Autowired jdbcTemplate: JdbcTemplate) {
            jdbcTemplate.execute(
                """
                CREATE TABLE IF NOT EXISTS bench_account (
                  id BIGINT PRIMARY KEY,
                  balance BIGINT NOT NULL,
                  version BIGINT NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        private fun parseConcurrency(property: String?): IntArray {
            if (property.isNullOrBlank()) {
                return DEFAULT_CONCURRENCY
            }
            return property.split(",").map { it.trim().toInt() }.toIntArray()
        }

        private fun printMarkdownTable(results: List<BenchmarkResult>) {
            val sb = StringBuilder()
            sb.append("\n")
            sb.append("| strategy | concurrency | tps | p50 (µs) | p95 (µs) | p99 (µs) | success | failure | retries |\n")
            sb.append("|---|---|---|---|---|---|---|---|---|\n")
            for (r in results) {
                sb.append(
                    "| %s | %d | %.1f | %d | %d | %d | %d | %d | %d |%n".format(
                        r.strategy, r.concurrency, r.tps, r.p50Micros, r.p95Micros, r.p99Micros,
                        r.successCount, r.failureCount, r.totalRetries
                    )
                )
            }
            println(sb)
        }
    }
}
