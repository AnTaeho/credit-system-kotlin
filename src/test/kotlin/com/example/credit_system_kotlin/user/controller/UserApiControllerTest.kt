package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.dto.BalanceResponse
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
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserApiControllerTest @Autowired constructor(
    private val restTemplate: TestRestTemplate,
    private val userRepository: UserRepository,
    private val ledgerRepository: LedgerRepository
) {

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("acme", 500L, email = DEV_USER))
    }

    @AfterEach
    fun tearDown() {
        ledgerRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `잔액 조회가 정상 동작한다`() {
        val headers = HttpHeaders()
        headers.add("X-Dev-User", DEV_USER)

        val response = restTemplate.exchange(
            url("/api/users/me/balance"), HttpMethod.GET,
            HttpEntity<Void>(headers), BalanceResponse::class.java
        )

        assertThat(response.body?.balance).isEqualTo(500L)
    }

    /**
     * 결제 확인 없이 잔액을 더하던 자기 충전은 없앴다(9-C). 인증된 사용자가 불러도
     * 받아 줄 핸들러가 없어 404 이고, 잔액과 원장은 그대로다.
     */
    @Test
    fun `자기 충전 경로는 더 이상 없다`() {
        val headers = HttpHeaders()
        headers.add("X-Dev-User", DEV_USER)
        headers.contentType = MediaType.APPLICATION_JSON

        val response = restTemplate.exchange(
            url("/api/users/me/charge"), HttpMethod.POST,
            HttpEntity("""{"idemKey":"idem-1","amount":300}""", headers), String::class.java
        )

        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(userRepository.findById(user.persistedId).orElseThrow().balance).isEqualTo(500L)
        assertThat(ledgerRepository.findByUserIdOrderByIdDesc(user.persistedId)).isEmpty()
    }

    private fun url(path: String) = "http://localhost:$port$path"

    companion object {
        /** application-test.yml 의 허용 목록에 있는 이메일. 개발 로그인 헤더로 이 사람이 된다. */
        private const val DEV_USER = "user@test.local"
    }
}
