package com.example.credit_system_kotlin.job.controller

import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.repository.IdempotencyKeyRepository
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.observability.DefenseMetrics
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/** 이 클래스 컨텍스트의 분당 접수 한도. 애너테이션 인자에 쓰려고 최상위 상수로 둔다. */
private const val LIMIT = 3

/**
 * job 접수 속도 제한을 HTTP 로 확인한다.
 *
 * 한도를 작게(분당 [LIMIT]) 준 컨텍스트는 이 클래스 하나만 쓴다. 테스트마다 값을 바꾸면 컨텍스트가
 * 늘어나므로 값은 하나로 둔다. 버킷은 앱 메모리에 있어 테스트 사이에 남지만, 테스트마다 사용자를
 * 새로 만들어 id 가 달라지므로 서로 영향을 주지 않는다.
 */
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.rate-limit.job-create.per-minute=$LIMIT"]
)
class JobCreateRateLimitTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository,
    private val ledgerRepository: LedgerRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val meterRegistry: MeterRegistry
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("limited", 10_000L, email = DEV_USER))
        other = userRepository.save(User("other", 10_000L, email = OTHER_USER))
    }

    @AfterEach
    fun tearDown() {
        val mine = setOf(user.persistedId, other.persistedId)
        idempotencyKeyRepository.deleteAll(idempotencyKeyRepository.findAll().filter { it.userId in mine })
        ledgerRepository.deleteAll(ledgerRepository.findAll().filter { it.userId in mine })
        jobRepository.deleteAll(jobRepository.findAll().filter { it.userId in mine })
        userRepository.deleteAll(listOf(user, other))
    }

    @Test
    fun `한도를 넘은 접수는 429 와 RATE_LIMITED, Retry-After 를 돌려준다`() {
        repeat(LIMIT) { assertThat(post(DEV_USER, "ok-$it").statusCode).isEqualTo(HttpStatus.OK) }

        val rejected = post(DEV_USER, "over")

        assertThat(rejected.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(rejected.body?.code).isEqualTo("RATE_LIMITED")
        val retryAfter = rejected.headers.getFirst(HttpHeaders.RETRY_AFTER)?.toLong()
        assertThat(retryAfter).isBetween(1L, SECONDS_PER_TOKEN)
    }

    @Test
    fun `거절된 접수는 원장·job·멱등키·잔액에 흔적을 남기지 않는다`() {
        repeat(LIMIT) { post(DEV_USER, "ok-$it") }
        val balanceBefore = balanceOf(user)
        val jobsBefore = jobRepository.findByUserIdOrderByIdDesc(user.persistedId).size
        val ledgerBefore = ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId).size

        val rejected = post(DEV_USER, "over")

        assertThat(rejected.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(balanceOf(user)).isEqualTo(balanceBefore)
        assertThat(jobRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(jobsBefore)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).hasSize(ledgerBefore)
        assertThat(idempotencyKeyRepository.findByUserIdAndIdemKey(user.persistedId, "over")).isNull()
    }

    @Test
    fun `한 사용자가 막혀도 다른 사용자는 접수된다`() {
        repeat(LIMIT) { post(DEV_USER, "ok-$it") }
        assertThat(post(DEV_USER, "over").statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

        assertThat(post(OTHER_USER, "other-1").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `목록 조회는 속도 제한을 받지 않는다`() {
        repeat(LIMIT) { post(DEV_USER, "ok-$it") }
        assertThat(post(DEV_USER, "over").statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)

        repeat(LIMIT * 3) {
            val response = restTemplate.exchange(
                url("/api/jobs"), HttpMethod.GET, HttpEntity<Void>(headers(DEV_USER)), String::class.java
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        }
    }

    @Test
    fun `통과와 거절이 credit_defense point=rate_limit 로 세어진다`() {
        val appliedBefore = defenseCount("applied")
        val rejectedBefore = defenseCount("rejected")

        repeat(LIMIT + 2) { post(DEV_USER, "m-$it") }

        assertThat(defenseCount("applied") - appliedBefore).isEqualTo(LIMIT.toDouble())
        assertThat(defenseCount("rejected") - rejectedBefore).isEqualTo(2.0)
    }

    private fun defenseCount(outcome: String): Double =
        meterRegistry.get(DefenseMetrics.DEFENSE_METRIC)
            .tag("point", "rate_limit")
            .tag("outcome", outcome)
            .counter()
            .count()

    private fun balanceOf(target: User): Long =
        userRepository.findById(target.persistedId).orElseThrow().balance

    /** 200(HoldResult)과 429(ErrorResponse)를 한 모양으로 받으려고 필드를 전부 비워 둘 수 있게 했다. */
    private data class AnyBody(val code: String? = null, val message: String? = null, val jobId: Long? = null)

    private fun post(email: String, idemKey: String): ResponseEntity<AnyBody> =
        restTemplate.exchange(
            url("/api/jobs"), HttpMethod.POST,
            HttpEntity(
                JobCreateRequest(idemKey, "a cat"),
                headers(email).apply { contentType = MediaType.APPLICATION_JSON }
            ),
            AnyBody::class.java
        )

    private fun headers(email: String) = HttpHeaders().apply { add("X-Dev-User", email) }

    private fun url(path: String) = "http://localhost:$port$path"

    companion object {
        /** 분당 [LIMIT] 개면 토큰 하나가 차는 데 걸리는 초. Retry-After 의 상한이다. */
        private const val SECONDS_PER_TOKEN = 60L / LIMIT

        /** application-test.yml 허용 목록의 이메일. 개발 로그인 헤더로 이 사람이 된다. */
        private const val DEV_USER = "user@test.local"
        private const val OTHER_USER = "admin@test.local"
    }
}
