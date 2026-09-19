package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.global.exception.ErrorResponse
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.event.LedgerReconciliationCompleted
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.ledger.scheduling.LedgerReconciliationTask
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.dto.GrantRequest
import com.example.credit_system_kotlin.user.dto.GrantResponse
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminGrantApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var target: User

    @BeforeEach
    fun setUp() {
        target = userRepository.save(User("target", 500L, email = USER_EMAIL))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `운영자가 지급하면 잔액이 오르고 원장에 ADMIN_GRANT 양수 행이 하나 남는다`() {
        val response = grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-1", 300L), GrantResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body).isEqualTo(GrantResponse(balance = 800L, duplicate = false))
        assertThat(balanceOf(target)).isEqualTo(800L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId))
            .singleElement()
            .satisfies({
                assertThat(it.type).isEqualTo(LedgerType.ADMIN_GRANT)
                assertThat(it.amount).isEqualTo(300L)
                assertThat(it.idemKey).isEqualTo("grant-1")
            })
    }

    @Test
    fun `같은 idemKey 재시도는 duplicate 이고 잔액과 원장은 한 번만 움직인다`() {
        val first = grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-dup", 300L), GrantResponse::class.java)
        val second = grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-dup", 300L), GrantResponse::class.java)

        assertThat(first.body).isEqualTo(GrantResponse(balance = 800L, duplicate = false))
        assertThat(second.body).isEqualTo(GrantResponse(balance = 800L, duplicate = true))
        assertThat(balanceOf(target)).isEqualTo(800L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).hasSize(1)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, 1_000_001L])
    fun `금액이 0 이하이거나 상한을 넘으면 400 이고 원장 행이 없다`(amount: Long) {
        val response = grant(
            ADMIN_EMAIL, target.persistedId, GrantRequest("grant-bad", amount), ErrorResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(balanceOf(target)).isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).isEmpty()
    }

    @Test
    fun `상한과 같은 금액은 지급된다`() {
        val response = grant(
            ADMIN_EMAIL, target.persistedId, GrantRequest("grant-max", 1_000_000L), GrantResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(balanceOf(target)).isEqualTo(1_000_500L)
    }

    /**
     * Java 원본은 UserServiceTest 에서 idemKey에 null을 넘겨 이 경계를 확인했다.
     * Kotlin은 idemKey를 non-null로 닫아(report.md A-4) 그 호출이 컴파일되지 않으므로
     * 남은 실제 경로 — 필드가 아예 없는 JSON — 를 여기서 확인한다(옛 charge 에서 옮겨 왔다).
     */
    @Test
    fun `idemKey 필드가 없는 본문은 400으로 거부된다`() {
        val response = grant(ADMIN_EMAIL, target.persistedId, """{"amount":300}""", ErrorResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(balanceOf(target)).isEqualTo(500L)
    }

    @Test
    fun `없는 사용자에게 지급하면 404 USER_NOT_FOUND 이고 원장 행이 없다`() {
        val missingUserId = target.persistedId + 999_999L

        val response = grant(ADMIN_EMAIL, missingUserId, GrantRequest("grant-missing", 300L), ErrorResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(response.body?.code).isEqualTo("USER_NOT_FOUND")
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(missingUserId)).isEmpty()
    }

    @Test
    fun `일반 사용자가 지급을 부르면 403 JSON 이고 돈이 움직이지 않는다`() {
        val response = grant(
            USER_EMAIL, target.persistedId, GrantRequest("grant-self", 300L), ErrorResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(403)
        assertThat(response.body?.code).isEqualTo("FORBIDDEN")
        assertThat(balanceOf(target)).isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).isEmpty()
    }

    /**
     * 미인증 요청은 CSRF 토큰을 실었을 때 인가 단계까지 가서 401 이 된다. 토큰이 없으면 그보다 앞선
     * CSRF 필터가 403 으로 먼저 막는다(9-B 규칙 그대로). 어느 쪽이든 돈은 움직이지 않는다.
     */
    @Test
    fun `미인증 지급 요청은 CSRF 토큰이 있으면 401 JSON 이다`() {
        val result = mockMvc.perform(
            post("/api/admin/users/${target.persistedId}/grants")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idemKey":"grant-anon","amount":300}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.contentAsString).contains("UNAUTHENTICATED")
        assertThat(balanceOf(target)).isEqualTo(500L)
    }

    @Test
    fun `미인증 지급 요청에 CSRF 토큰이 없으면 CSRF 필터가 403 으로 먼저 막는다`() {
        val response = grant(null, target.persistedId, GrantRequest("grant-anon", 300L), ErrorResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(403)
        assertThat(response.body?.code).isEqualTo("FORBIDDEN")
        assertThat(balanceOf(target)).isEqualTo(500L)
    }

    /** 지급 원장은 양수여야 대사(`balance == initialBalance + SUM(amount)`)가 맞는다. */
    @Test
    fun `지급 뒤 원장 대사 불일치가 없다`() {
        grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-r1", 300L), GrantResponse::class.java)
        grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-r2", 200L), GrantResponse::class.java)
        grant(ADMIN_EMAIL, target.persistedId, GrantRequest("grant-r2", 200L), GrantResponse::class.java)
        val eventPublisher: ApplicationEventPublisher = mock()

        LedgerReconciliationTask(ledgerRepository, eventPublisher).reconcile()

        val captor = argumentCaptor<LedgerReconciliationCompleted>()
        verify(eventPublisher).publishEvent(captor.capture())
        assertThat(captor.firstValue.checkedCount).isGreaterThanOrEqualTo(1)
        assertThat(captor.firstValue.mismatchCount).isEqualTo(0)
        assertThat(balanceOf(target)).isEqualTo(1_000L)
    }

    private fun <T : Any> grant(devUser: String?, userId: Long, body: Any, type: Class<T>): ResponseEntity<T> {
        val headers = HttpHeaders()
        devUser?.let { headers.add("X-Dev-User", it) }
        headers.contentType = MediaType.APPLICATION_JSON
        return restTemplate.exchange(
            url("/api/admin/users/$userId/grants"), HttpMethod.POST, HttpEntity(body, headers), type
        )
    }

    private fun balanceOf(user: User): Long = userRepository.findById(user.persistedId).orElseThrow().balance

    private fun url(path: String) = "http://localhost:$port$path"

    companion object {
        /** application-test.yml 의 허용 목록에만 있는 이메일(ROLE_USER). */
        private const val USER_EMAIL = "user@test.local"

        /** application-test.yml 의 운영자 목록에 있는 이메일(ROLE_ADMIN). */
        private const val ADMIN_EMAIL = "admin@test.local"
    }
}
