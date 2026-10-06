package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.global.exception.ErrorResponse
import com.example.credit_system_kotlin.ledger.domain.LedgerType
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.support.withCsrfToken
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.dto.GrantRequest
import com.example.credit_system_kotlin.user.dto.GrantResponse
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    private val ledgerRepository: LedgerRepository,
    accessTokenService: AccessTokenService
) {

    private val tokens = TestTokens(accessTokenService)

    @field:LocalServerPort
    private var port: Int = 0

    /** 지급 대상이자, 지급을 부를 수 없는 일반 사용자. */
    private lateinit var target: User

    private lateinit var admin: User

    @BeforeEach
    fun setUp() {
        target = userRepository.save(User("target", 500L, email = "user@test.local"))
        admin = userRepository.save(User("admin", 0L, email = "admin@test.local", role = UserRole.ADMIN))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `운영자가 지급하면 잔액이 오르고 원장에 ADMIN_GRANT 양수 행이 하나 남는다`() {
        val response = grant(admin, target.persistedId, GrantRequest("grant-1", 300L), GrantResponse::class.java)

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
        val first = grant(admin, target.persistedId, GrantRequest("grant-dup", 300L), GrantResponse::class.java)
        val second = grant(admin, target.persistedId, GrantRequest("grant-dup", 300L), GrantResponse::class.java)

        assertThat(first.body).isEqualTo(GrantResponse(balance = 800L, duplicate = false))
        assertThat(second.body).isEqualTo(GrantResponse(balance = 800L, duplicate = true))
        assertThat(balanceOf(target)).isEqualTo(800L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).hasSize(1)
    }

    @ParameterizedTest
    @ValueSource(longs = [0L, -1L, 1_000_001L])
    fun `금액이 0 이하이거나 상한을 넘으면 400 이고 원장 행이 없다`(amount: Long) {
        val response = grant(
            admin, target.persistedId, GrantRequest("grant-bad", amount), ErrorResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(balanceOf(target)).isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).isEmpty()
    }

    @Test
    fun `상한과 같은 금액은 지급된다`() {
        val response = grant(
            admin, target.persistedId, GrantRequest("grant-max", 1_000_000L), GrantResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(balanceOf(target)).isEqualTo(1_000_500L)
    }

    /** idemKey 가 non-null 이라 null 을 넘기는 호출은 컴파일되지 않는다. 필드가 아예 없는 JSON 으로 같은 경계를 본다. */
    @Test
    fun `idemKey 필드가 없는 본문은 400으로 거부된다`() {
        val response = grant(admin, target.persistedId, """{"amount":300}""", ErrorResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_REQUEST")
        assertThat(balanceOf(target)).isEqualTo(500L)
    }

    @Test
    fun `없는 사용자에게 지급하면 404 USER_NOT_FOUND 이고 원장 행이 없다`() {
        val missingUserId = target.persistedId + 999_999L

        val response = grant(admin, missingUserId, GrantRequest("grant-missing", 300L), ErrorResponse::class.java)

        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(response.body?.code).isEqualTo("USER_NOT_FOUND")
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(missingUserId)).isEmpty()
    }

    @Test
    fun `일반 사용자가 지급을 부르면 403 JSON 이고 돈이 움직이지 않는다`() {
        val response = grant(
            target, target.persistedId, GrantRequest("grant-self", 300L), ErrorResponse::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(403)
        assertThat(response.body?.code).isEqualTo("FORBIDDEN")
        assertThat(balanceOf(target)).isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(target.persistedId)).isEmpty()
    }

    /** CSRF 토큰이 없으면 앞선 CSRF 필터가 403 으로 먼저 막는다. 토큰을 실어야 인가 단계까지 가서 401 이 된다. */
    @Test
    fun `미인증 지급 요청은 CSRF 토큰이 있으면 401 JSON 이다`() {
        val result = mockMvc.perform(
            post("/api/admin/users/${target.persistedId}/grants")
                .withCsrfToken()
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

    /** [actor] 로 로그인해(Bearer 헤더) 지급을 부른다. null 이면 미인증이다. */
    private fun <T : Any> grant(actor: User?, userId: Long, body: Any, type: Class<T>): ResponseEntity<T> {
        val headers = actor?.let(tokens::bearerHeaders) ?: HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return restTemplate.exchange(
            url("/api/admin/users/$userId/grants"), HttpMethod.POST, HttpEntity(body, headers), type
        )
    }

    private fun balanceOf(user: User): Long = userRepository.findById(user.persistedId).orElseThrow().balance

    private fun url(path: String) = "http://localhost:$port$path"
}
