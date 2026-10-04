package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.support.TestTokens
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/**
 * 진짜 서블릿 컨테이너에서 어떤 응답에도 `JSESSIONID` 가 실리지 않는지 본다.
 *
 * MockMvc 에는 세션 쿠키를 내보내는 컨테이너가 없어 이 쿠키가 보이지 않는다. MockMvc 쪽에서는
 * SecurityRulesTest 가 "세션 객체가 만들어지지 않는다"를 본다. 여기는 그 결과가 응답 헤더에도 그대로인지다.
 * 컨텍스트는 API 컨트롤러 테스트들과 같은 조합이라 캐시를 같이 쓴다.
 */
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StatelessSessionTest @Autowired constructor(
    restTemplate: TestRestTemplate,
    private val userRepository: UserRepository,
    accessTokenService: AccessTokenService
) {

    private val restTemplate = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)

    private val tokens = TestTokens(accessTokenService)

    @field:LocalServerPort
    private var port: Int = 0

    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = userRepository.save(User("stateless", 0L, email = "stateless@test.local"))
    }

    @AfterEach
    fun tearDown() {
        userRepository.delete(user)
    }

    @Test
    fun `로그인 화면·미인증 요청·쿠키 인증·Bearer 인증·토큰 발급 어느 응답에도 JSESSIONID 가 없다`() {
        val cookieAuth = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, "${AuthCookies.ACCESS}=${tokens.accessToken(user)}")
        }
        val json = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val responses = mapOf(
            "로그인 화면" to exchange(HttpMethod.GET, "/login", HttpEntity<Void>(HttpHeaders())),
            "미인증 화면" to exchange(HttpMethod.GET, "/", HttpEntity<Void>(HttpHeaders())),
            "미인증 api" to exchange(HttpMethod.GET, "/api/users/me/balance", HttpEntity<Void>(HttpHeaders())),
            "쿠키 인증 화면" to exchange(HttpMethod.GET, "/", HttpEntity<Void>(cookieAuth)),
            "쿠키 인증 api" to exchange(HttpMethod.GET, "/api/users/me/balance", HttpEntity<Void>(cookieAuth)),
            "Bearer 인증 api" to
                exchange(HttpMethod.GET, "/api/users/me/balance", HttpEntity<Void>(tokens.bearerHeaders(user))),
            "토큰 발급 실패" to exchange(
                HttpMethod.POST, "/auth/token",
                HttpEntity("""{"email":"stateless@test.local","password":"wrong-password"}""", json)
            )
        )

        assertThat(responses.mapValues { it.value.statusCode.value() }).isEqualTo(
            mapOf(
                "로그인 화면" to 200,
                "미인증 화면" to 302,
                "미인증 api" to 401,
                "쿠키 인증 화면" to 200,
                "쿠키 인증 api" to 200,
                "Bearer 인증 api" to 200,
                "토큰 발급 실패" to 401
            )
        )
        responses.forEach { (name, response) ->
            assertThat(response.headers[HttpHeaders.SET_COOKIE].orEmpty()).`as`(name).noneMatch {
                it.contains("JSESSIONID")
            }
        }
        // 화면은 CSRF 토큰 쿠키만 심는다.
        assertThat(responses.getValue("로그인 화면").headers[HttpHeaders.SET_COOKIE].orEmpty())
            .singleElement().matches {
                it.startsWith("XSRF-TOKEN=") && it.contains("HttpOnly") &&
                    it.contains("SameSite=Lax")
            }
    }

    private fun exchange(method: HttpMethod, path: String, entity: HttpEntity<*>): ResponseEntity<String> =
        restTemplate.exchange("http://localhost:$port$path", method, entity, String::class.java)
}
