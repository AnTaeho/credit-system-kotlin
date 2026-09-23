package com.example.credit_system_kotlin.job.concurrency

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * 동시성 테스트들이 공유하는 MySQL / Redis 컨테이너.
 *
 * Java 원본은 abstract class의 static 초기화 블록을 상속으로 공유했지만,
 * Kotlin에서는 companion object 멤버가 하위 클래스로 상속되지 않으므로
 * object 하나로 두고 각 테스트가 명시적으로 호출한다.
 *
 * 컨테이너는 이 object에 처음 접근할 때(=registerDatabase 호출 시) 뜨고,
 * withReuse(true) 라서 테스트 클래스마다 다시 뜨지 않는다.
 */
object SharedContainers {

    /**
     * GenericContainer는 self-type 제네릭(`SELF extends GenericContainer<SELF>`)이라
     * Kotlin에서 그대로 쓰면 빌더 메서드 반환 타입이 Nothing이 되어 이후 코드가
     * 도달 불가로 취급된다. SELF를 묶어 줄 구체 하위 클래스를 하나 두어 피한다.
     */
    private class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image)

    /**
     * MySQLContainer는 testcontainers 2.x 에서 self-type 제네릭이 없는
     * `org.testcontainers.mysql.MySQLContainer` 가 새로 생겼다. Java 원본이 쓰던
     * `org.testcontainers.containers.MySQLContainer<?>` 도 아직 남아 있지만,
     * Kotlin에서는 위와 같은 이유로 새 쪽이 훨씬 깔끔하다.
     */
    private val mysql: MySQLContainer = MySQLContainer("mysql:8.4")
        .withDatabaseName("credit_system")
        .withUsername("credit")
        .withPassword("credit")
        // V6 가 `ledger_entries` 에 트리거를 만든다. 이 이미지는 log_bin=1 이고 앱 계정
        // `credit` 에는 SUPER 가 없어서, 기본값(log_bin_trust_function_creators=0)에서는
        // CREATE TRIGGER 가 ERROR 1419 로 막힌다(docs/02-design.md 1-4 "INV-06 강제 장치").
        // Flyway 도 같은 계정으로 돌기 때문에 이 플래그가 없으면 마이그레이션 자체가 실패한다.
        // 이미지 기본 CMD 는 `mysqld` 뿐이라 덮어쓸 다른 옵션이 없다 — 엔트리포인트가
        // `--` 로 시작하는 인자 앞에 mysqld 를 붙여 준다.
        .withCommand("--log-bin-trust-function-creators=1")
        .withReuse(true)

    private val redis: RedisContainer = RedisContainer(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(REDIS_PORT)
        .withReuse(true)

    init {
        mysql.start()
        redis.start()
        flushRedis()
    }

    fun registerDatabase(registry: DynamicPropertyRegistry, database: String) {
        val properties = propertiesFor(database)
        for ((key, value) in properties) {
            registry.add(key) { value }
        }
    }

    /**
     * `@DynamicPropertySource` 를 못 쓰는 테스트 — 스프링 테스트 컨텍스트에 맡기지 않고
     * 애플리케이션을 직접 띄웠다 내리는 종료 테스트 — 를 위해 같은 값을 맵으로도 내준다.
     */
    fun propertiesFor(database: String): Map<String, Any> {
        createDatabase(database)
        return mapOf(
            "spring.datasource.url" to jdbcUrlFor(database),
            "spring.datasource.username" to mysql.username,
            "spring.datasource.password" to mysql.password,
            "spring.datasource.driver-class-name" to mysql.driverClassName,
            // H2 테스트와 달리 여기서는 스키마를 Flyway 가 만들고 Hibernate 가 검증한다.
            // 마이그레이션이 실제 MySQL 에서 돌아가는지, 그 결과가 엔티티 매핑과 맞는지를
            // 확인하는 자리가 이 테스트들뿐이다.
            "spring.flyway.enabled" to "true",
            "spring.jpa.hibernate.ddl-auto" to "validate",
            "spring.data.redis.host" to redis.host,
            "spring.data.redis.port" to redis.getMappedPort(REDIS_PORT)
        )
    }

    private fun flushRedis() {
        try {
            redis.execInContainer("redis-cli", "FLUSHALL")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Failed to flush redis", e)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to flush redis", e)
        }
    }

    private fun createDatabase(database: String) {
        try {
            DriverManager.getConnection(jdbcUrlFor("mysql"), "root", mysql.password).use { connection ->
                connection.createStatement().use { statement ->
                    // 컨테이너가 withReuse(true) 라 지난 실행의 테이블이 남아 있다.
                    // Flyway 는 이력 테이블 없이 채워진 스키마를 만나면 멈추므로 매번 비우고 시작한다.
                    statement.execute("DROP DATABASE IF EXISTS $database")
                    statement.execute("CREATE DATABASE $database")
                    statement.execute("GRANT ALL PRIVILEGES ON $database.* TO 'credit'@'%'")
                    statement.execute("FLUSH PRIVILEGES")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create database $database", e)
        }
    }

    private fun jdbcUrlFor(database: String): String {
        val jdbcUrl = mysql.jdbcUrl
        val schemeEnd = jdbcUrl.indexOf("://") + 3
        val hostEnd = jdbcUrl.indexOf('/', schemeEnd)
        val paramsStart = jdbcUrl.indexOf('?', hostEnd)
        val prefix = jdbcUrl.substring(0, hostEnd + 1)
        val params = if (paramsStart == -1) "" else jdbcUrl.substring(paramsStart)
        return prefix + database + params
    }

    private const val REDIS_PORT = 6379
}
