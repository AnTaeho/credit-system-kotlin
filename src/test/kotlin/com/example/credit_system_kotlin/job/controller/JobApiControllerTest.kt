package com.example.credit_system_kotlin.job.controller

import com.example.credit_system_kotlin.global.exception.ErrorResponse
import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.job.domain.Job
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.dto.JobResponse
import com.example.credit_system_kotlin.job.repository.JobRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val userRepository: UserRepository,
    private val jobRepository: JobRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var user: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        // 같은 H2 를 쓰는 다른 테스트가 남긴 job 이 이 사용자 id 와 겹치지 않게 비우고 시작한다.
        jobRepository.deleteAll()
        user = userRepository.save(User("acme", 1000L, email = DEV_USER))
        other = userRepository.save(User("other", 1000L, email = "other@test.local"))
    }

    @AfterEach
    fun tearDown() {
        jobRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `인증 없이 호출하면 401이다`() {
        val response = restTemplate.getForEntity(url("/api/jobs"), ErrorResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body?.code).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `생성 요청과 목록 조회가 정상 동작한다`() {
        val headers = authHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val createResponse = restTemplate.exchange(
            url("/api/jobs"), HttpMethod.POST,
            HttpEntity(JobCreateRequest("idem-1", "a cat"), headers),
            HoldResult::class.java
        )

        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(createResponse.body?.duplicate).isFalse()

        val page = getPage("/api/jobs")

        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].status).isEqualTo("HOLDING")
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `내 job 은 단건으로 조회된다`() {
        val job = jobRepository.save(Job.hold(user.persistedId, 100L, "a cat"))

        val response = restTemplate.exchange(
            url("/api/jobs/${job.persistedId}"), HttpMethod.GET, HttpEntity<Void>(authHeaders()),
            JobResponse::class.java
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.id).isEqualTo(job.persistedId)
        assertThat(response.body?.status).isEqualTo("HOLDING")
        assertThat(response.body?.prompt).isEqualTo("a cat")
    }

    @Test
    fun `남의 job 은 없는 job 과 같이 404다`() {
        val job = jobRepository.save(Job.hold(other.persistedId, 100L, "not mine"))

        val response = getError("/api/jobs/${job.persistedId}")

        assertThat(response.first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.second?.code).isEqualTo("JOB_NOT_FOUND")
    }

    @Test
    fun `없는 job 은 404다`() {
        val response = getError("/api/jobs/999999")

        assertThat(response.first).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.second?.code).isEqualTo("JOB_NOT_FOUND")
    }

    @Test
    fun `숫자가 아닌 job id 는 다른 400 과 같은 본문이다`() {
        val response = getError("/api/jobs/abc")

        assertThat(response.first).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.second?.code).isEqualTo("INVALID_REQUEST")
    }

    @Test
    fun `단건 조회도 인증 없이 호출하면 401이다`() {
        val job = jobRepository.save(Job.hold(user.persistedId, 100L, "a cat"))

        val response = restTemplate.getForEntity(url("/api/jobs/${job.persistedId}"), ErrorResponse::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `커서로 끝까지 빠짐과 중복 없이 순회한다`() {
        val ids = saveJobs(user, 25)

        val first = getPage("/api/jobs?size=10")
        val second = getPage("/api/jobs?size=10&cursor=${first.nextCursor}")
        val third = getPage("/api/jobs?size=10&cursor=${second.nextCursor}")

        assertThat(first.items).hasSize(10)
        assertThat(second.items).hasSize(10)
        assertThat(third.items).hasSize(5)
        assertThat(first.nextCursor).isEqualTo(first.items.last().id)
        assertThat(second.nextCursor).isEqualTo(second.items.last().id)
        assertThat(third.nextCursor).isNull()
        val visited = (first.items + second.items + third.items).map { it.id }
        assertThat(visited).containsExactlyElementsOf(ids.sortedDescending())
    }

    @Test
    fun `정확히 size 개만 있으면 nextCursor 는 null 이다`() {
        saveJobs(user, 10)

        val page = getPage("/api/jobs?size=10")

        assertThat(page.items).hasSize(10)
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `다른 사용자의 job 이 섞여 있어도 내 것만 돌려준다`() {
        val mine = saveJobs(user, 3)
        saveJobs(other, 3)
        jobRepository.save(Job.hold(user.persistedId, 100L, "mine-last"))
            .also { mine.add(it.persistedId) }

        val page = getPage("/api/jobs")

        assertThat(page.items.map { it.id }).containsExactlyElementsOf(mine.sortedDescending())
        assertThat(page.nextCursor).isNull()
    }

    @Test
    fun `size 를 주지 않으면 20개씩이다`() {
        saveJobs(user, 25)

        val page = getPage("/api/jobs")

        assertThat(page.items).hasSize(20)
        assertThat(page.nextCursor).isEqualTo(page.items.last().id)
    }

    @Test
    fun `size 와 cursor 가 범위를 벗어나거나 숫자가 아니면 400이다`() {
        listOf("size=101", "size=0", "size=-1", "size=abc", "cursor=0", "cursor=-5", "cursor=abc").forEach { query ->
            val response = getError("/api/jobs?$query")

            assertThat(response.first).`as`(query).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.second?.code).`as`(query).isEqualTo("INVALID_REQUEST")
        }
    }

    private fun saveJobs(owner: User, count: Int): MutableList<Long> =
        (1..count).map { jobRepository.save(Job.hold(owner.persistedId, 100L, "prompt-$it")).persistedId }
            .toMutableList()

    private fun getPage(path: String): CursorPage<JobResponse> {
        val response = restTemplate.exchange(
            url(path), HttpMethod.GET, HttpEntity<Void>(authHeaders()),
            object : ParameterizedTypeReference<CursorPage<JobResponse>>() {}
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body)
    }

    private fun getError(path: String): Pair<HttpStatus, ErrorResponse?> {
        val response = restTemplate.exchange(
            url(path), HttpMethod.GET, HttpEntity<Void>(authHeaders()), ErrorResponse::class.java
        )
        return HttpStatus.valueOf(response.statusCode.value()) to response.body
    }

    private fun authHeaders() = HttpHeaders().apply { add("X-Dev-User", DEV_USER) }

    private fun url(path: String) = "http://localhost:$port$path"

    companion object {
        /** application-test.yml 의 허용 목록에 있는 이메일. 개발 로그인 헤더로 이 사람이 된다. */
        private const val DEV_USER = "user@test.local"
    }
}
