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
        createDatabase(database)
        registry.add("spring.datasource.url") { jdbcUrlFor(database) }
        registry.add("spring.datasource.username") { mysql.username }
        registry.add("spring.datasource.password") { mysql.password }
        registry.add("spring.datasource.driver-class-name") { mysql.driverClassName }
        registry.add("spring.data.redis.host") { redis.host }
        registry.add("spring.data.redis.port") { redis.getMappedPort(REDIS_PORT) }
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
                    statement.execute("CREATE DATABASE IF NOT EXISTS $database")
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
