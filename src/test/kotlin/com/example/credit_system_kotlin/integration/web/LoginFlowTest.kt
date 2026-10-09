package com.example.credit_system_kotlin.integration.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.dto.SignUpRequest
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenStore
import com.example.credit_system_kotlin.auth.web.AuthCookies
import com.example.credit_system_kotlin.support.SharedContainers
import com.example.credit_system_kotlin.support.jwtClaim
import com.example.credit_system_kotlin.support.withCsrfToken
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockCookie
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 가입·로그인·조용한 갱신·로그아웃을 화면이 쓰는 길 그대로(폼 POST 와 쿠키) 확인한다.
 * MockMvc 는 쿠키를 다음 요청에 실어 주지 않아 응답의 쿠키를 꺼내 직접 싣는다.
 */
// DB 는 H2 이고 리프레시 토큰이 놓이는 Redis 만 Testcontainers 실물이다.
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class LoginFlowTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val accountService: AccountService,
    private val refreshTokenService: RefreshTokenService,
    private val userRepository: UserRepository,
    private val redisTemplate: StringRedisTemplate
) {

    @AfterEach
    fun tearDown() {
        redisTemplate.delete(refreshKeys())
        userRepository.deleteAll(userRepository.findAll().filter { it.email?.startsWith(EMAIL_PREFIX) == true })
    }

    @Test
    fun `가입하면 두 쿠키가 HttpOnly·SameSite=Lax 로 오고 그 쿠키로 홈이 열린다`() {
        val result = signUp(EMAIL, PASSWORD, PASSWORD)

        assertThat(result.response.status).isEqualTo(302)
        assertThat(result.response.redirectedUrl).isEqualTo("/")
        val access = loginCookie(result, AuthCookies.ACCESS)
        val refresh = loginCookie(result, AuthCookies.REFRESH)
        assertThat(access.maxAge).isEqualTo(60 * 60)
        assertThat(refresh.maxAge).isEqualTo(14 * 24 * 60 * 60)
        // 리프레시 쿠키는 JWT 이고, 그 jti 가 Redis 에 사용자 id 로 남는다.
        assertThat(jwtClaim(refresh.value, "typ")).isEqualTo("refresh")
        assertThat(jwtClaim(access.value, "typ")).isEqualTo("access")
        assertThat(redisTemplate.opsForValue().get(refreshKey(refresh)))
            .isEqualTo(userRepository.findByEmail(EMAIL)?.persistedId.toString())
        listOf(access, refresh).forEach {
            assertThat(it.isHttpOnly).`as`(it.name).isTrue()
            assertThat(it.sameSite).`as`(it.name).isEqualTo("Lax")
            assertThat(it.path).`as`(it.name).isEqualTo("/")
            assertThat(it.secure).`as`(it.name).isFalse()
        }

        val home = mockMvc.perform(get("/").cookie(access)).andReturn()
        assertThat(home.response.status).isEqualTo(200)
        assertThat(home.response.contentAsString).contains(EMAIL)
        // 가입은 항상 일반 사용자다.
        assertThat(userRepository.findByEmail(EMAIL)?.role).isEqualTo(UserRole.USER)
        assertThat(mockMvc.perform(get("/admin").cookie(access)).andReturn().response.status).isEqualTo(403)
    }

    @Test
    fun `두 비밀번호가 다르면 400 으로 가입 화면을 다시 그리고 계정을 만들지 않는다`() {
        val result = signUp(EMAIL, PASSWORD, "another-password")

        assertSignUpRejected(result, 400, "비밀번호가 서로 다릅니다.")
        assertThat(userRepository.findByEmail(EMAIL)).isNull()
    }

    @Test
    fun `비밀번호가 규칙에 맞지 않으면 400 으로 그 이유를 보이고 계정을 만들지 않는다`() {
        val result = signUp(EMAIL, "1234567", "1234567")

        assertSignUpRejected(result, 400, "비밀번호는 8자 이상이어야 합니다.")
        assertThat(userRepository.findByEmail(EMAIL)).isNull()
    }

    @Test
    fun `이미 가입된 이메일이면 409 이고 먼저 가입한 계정은 그대로다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        val result = signUp(EMAIL, "intruder-password", "intruder-password")

        assertSignUpRejected(result, 409, "이미 가입된 이메일입니다.")
        assertThat(userRepository.findAll().filter { it.email == EMAIL }).hasSize(1)
        assertThat(accountService.authenticate(EMAIL, PASSWORD)).isNotNull()
        assertThat(accountService.authenticate(EMAIL, "intruder-password")).isNull()
    }

    @Test
    fun `로그인하면 두 쿠키가 오고 홈으로 간다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        val result = logIn(EMAIL, PASSWORD)

        assertThat(result.response.status).isEqualTo(302)
        assertThat(result.response.redirectedUrl).isEqualTo("/")
        val access = loginCookie(result, AuthCookies.ACCESS)
        loginCookie(result, AuthCookies.REFRESH)
        assertThat(mockMvc.perform(get("/api/users/me/balance").cookie(access)).andReturn().response.status)
            .isEqualTo(200)
    }

    @Test
    fun `비밀번호가 틀리거나 없는 이메일이면 401 로 로그인 화면을 다시 그리고 쿠키를 주지 않는다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        listOf(EMAIL to "wrong-password", "${EMAIL_PREFIX}nobody@test.local" to PASSWORD).forEach { (email, password) ->
            val result = logIn(email, password)

            assertThat(result.response.status).`as`(email).isEqualTo(401)
            assertThat(result.response.contentAsString).`as`(email)
                .contains("이메일 또는 비밀번호가 맞지 않습니다.")
                .contains("value=\"$email\"")
                .doesNotContain(password)
            assertNoLoginCookies(result)
        }
        assertThat(refreshKeys()).isEmpty()
    }

    @Test
    fun `로그인과 가입 POST 는 CSRF 토큰이 없으면 403 이다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        val login = mockMvc.perform(post("/login").param("email", EMAIL).param("password", PASSWORD)).andReturn()
        val signUp = mockMvc.perform(
            post("/signup")
                .param("email", "${EMAIL_PREFIX}other@test.local")
                .param("password", PASSWORD)
                .param("passwordConfirm", PASSWORD)
        ).andReturn()

        listOf(login, signUp).forEach {
            assertThat(it.response.status).isEqualTo(403)
            assertNoLoginCookies(it)
        }
        assertThat(userRepository.findByEmail("${EMAIL_PREFIX}other@test.local")).isNull()
    }

    /** 테스트가 지어낸 토큰이 아니라, 화면이 실제로 받는 쿠키와 hidden 값으로 CSRF 검사를 넘는지 본다. */
    @Test
    fun `로그인 화면이 준 CSRF 쿠키와 hidden 값을 돌려보내면 로그인된다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val page = mockMvc.perform(get("/login")).andReturn()
        val csrfCookie = requireNotNull(page.response.getCookie("XSRF-TOKEN"))
        val hidden = requireNotNull(
            Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page.response.contentAsString)
        ).groupValues[1]

        val result = mockMvc.perform(
            post("/login").cookie(csrfCookie).param("_csrf", hidden).param("email", EMAIL).param("password", PASSWORD)
        ).andReturn()

        assertThat(result.response.status).isEqualTo(302)
        loginCookie(result, AuthCookies.ACCESS)
    }

    @Test
    fun `액세스 쿠키 없이 리프레시 쿠키만 있어도 화면이 열리고 액세스 쿠키만 새로 온다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)

        val result = mockMvc.perform(get("/").cookie(refresh)).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains(EMAIL)
        val newAccess = loginCookie(result, AuthCookies.ACCESS)
        assertThat(result.response.getCookie(AuthCookies.REFRESH)).`as`("리프레시 쿠키는 다시 쓰지 않는다").isNull()
        // 새로 받은 액세스만으로도, 처음 받은 리프레시만으로도 다음 요청이 된다.
        assertThat(mockMvc.perform(get("/api/users/me/balance").cookie(newAccess)).andReturn().response.status)
            .isEqualTo(200)
        assertThat(mockMvc.perform(get("/api/users/me/balance").cookie(refresh)).andReturn().response.status)
            .isEqualTo(200)
    }

    @Test
    fun `만료된 액세스 쿠키와 리프레시 쿠키가 같이 오면 액세스 쿠키만 새로 온다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)

        val result = mockMvc.perform(
            get("/api/users/me/balance").cookie(Cookie(AuthCookies.ACCESS, "expired-or-garbage"), refresh)
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(loginCookie(result, AuthCookies.ACCESS).value).isNotEqualTo("expired-or-garbage")
        assertThat(result.response.getCookie(AuthCookies.REFRESH)).isNull()
    }

    /** 탭 두 개가 같은 리프레시로 갱신하는 경우다. 토큰이 바뀌지 않으므로 몇 번을 써도 같은 한 장이 남는다. */
    @Test
    fun `같은 리프레시로 여러 번 갱신해도 매번 액세스 쿠키만 새로 오고 토큰은 한 장 그대로다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)

        repeat(3) {
            val result = mockMvc.perform(get("/api/users/me/balance").cookie(refresh)).andReturn()

            assertThat(result.response.status).isEqualTo(200)
            loginCookie(result, AuthCookies.ACCESS)
            assertThat(result.response.getCookie(AuthCookies.REFRESH)).isNull()
        }
        assertThat(refreshKeys()).containsExactly(refreshKey(refresh))
    }

    @Test
    fun `삭제된 리프레시면 두 쿠키가 지워지고 화면은 로그인으로, api 는 401 이다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)
        refreshTokenService.delete(refresh.value)

        val page = mockMvc.perform(get("/").cookie(refresh)).andReturn()
        val api = mockMvc.perform(get("/api/users/me/balance").cookie(refresh)).andReturn()

        assertThat(page.response.status).isEqualTo(302)
        assertThat(page.response.redirectedUrl).endsWith("/login")
        assertThat(api.response.status).isEqualTo(401)
        assertThat(api.response.contentAsString).contains("UNAUTHENTICATED")
        listOf(page, api).forEach(::assertLoginCookiesCleared)
    }

    @Test
    fun `모르는 리프레시 쿠키도 지워지고 미인증이다`() {
        val result = mockMvc.perform(
            get("/api/users/me/balance").cookie(Cookie(AuthCookies.REFRESH, "made-up"))
        ).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertLoginCookiesCleared(result)
    }

    // 두 JWT 가 같은 키로 서명되므로 종류가 다르면 받지 않는지 실제 요청으로 본다.
    @Test
    fun `리프레시 JWT 는 Bearer 나 액세스 쿠키로 쓸 수 없고 액세스 JWT 는 리프레시 쿠키로 쓸 수 없다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val login = logIn(EMAIL, PASSWORD)
        val access = loginCookie(login, AuthCookies.ACCESS).value
        val refresh = loginCookie(login, AuthCookies.REFRESH).value

        val asBearer = mockMvc.perform(
            get("/api/users/me/balance").header(HttpHeaders.AUTHORIZATION, "Bearer $refresh")
        ).andReturn()
        val asAccessCookie = mockMvc.perform(
            get("/api/users/me/balance").cookie(Cookie(AuthCookies.ACCESS, refresh))
        ).andReturn()
        val asRefreshCookie = mockMvc.perform(
            get("/api/users/me/balance").cookie(Cookie(AuthCookies.REFRESH, access))
        ).andReturn()

        assertThat(asBearer.response.status).`as`("리프레시를 Bearer 로").isEqualTo(401)
        assertThat(asAccessCookie.response.status).`as`("리프레시를 액세스 쿠키로").isEqualTo(401)
        assertThat(asRefreshCookie.response.status).`as`("액세스를 리프레시 쿠키로").isEqualTo(401)
        assertLoginCookiesCleared(asRefreshCookie)
    }

    /** 화면 하나가 css·js 를 같이 부른다. 그 요청들이 저마다 리프레시로 갱신하면 안 된다. */
    @Test
    fun `정적 리소스 요청은 리프레시로 갱신하지 않는다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)

        listOf("/css/app.css", "/js/app.js").forEach { path ->
            val result = mockMvc.perform(get(path).cookie(refresh)).andReturn()

            assertThat(result.response.status).`as`(path).isEqualTo(200)
            assertNoLoginCookies(result)
        }
        assertThat(refreshKeys()).containsExactly(refreshKey(refresh))
    }

    @Test
    fun `로그아웃하면 두 쿠키가 지워지고 그 리프레시로는 다시 갱신되지 않는다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))
        val refresh = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)
        // 다른 기기의 로그인이다. 이쪽 로그아웃에 끊기지 않는다.
        val otherDevice = loginCookie(logIn(EMAIL, PASSWORD), AuthCookies.REFRESH)

        val logout = mockMvc.perform(post("/logout").cookie(refresh).withCsrfToken()).andReturn()

        assertThat(logout.response.status).isEqualTo(302)
        assertThat(logout.response.redirectedUrl).isEqualTo("/login?logout")
        assertLoginCookiesCleared(logout)
        assertThat(mockMvc.perform(get("/api/users/me/balance").cookie(refresh)).andReturn().response.status)
            .isEqualTo(401)
        assertThat(mockMvc.perform(get("/api/users/me/balance").cookie(otherDevice)).andReturn().response.status)
            .isEqualTo(200)
        assertThat(refreshKeys()).containsExactly(refreshKey(otherDevice))
    }

    @Test
    fun `auth token 은 이메일과 비밀번호로 액세스 토큰을 주고 그 토큰은 Bearer 헤더로 쓰인다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        val result = mockMvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$EMAIL","password":"$PASSWORD"}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString).contains("\"expiresInSeconds\":3600")
        assertThat(result.response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
        assertThat(refreshKeys()).isEmpty()
        val token = requireNotNull(
            Regex("\"accessToken\":\"([^\"]+)\"").find(result.response.contentAsString)
        ).groupValues[1]
        val balance = mockMvc.perform(get("/api/users/me/balance").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andReturn()
        assertThat(balance.response.status).isEqualTo(200)
    }

    @Test
    fun `auth token 은 비밀번호가 틀리면 401 BAD_CREDENTIALS 이고 토큰을 주지 않는다`() {
        accountService.signUp(SignUpRequest(EMAIL, PASSWORD))

        val result = mockMvc.perform(
            post("/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$EMAIL","password":"wrong-password"}""")
        ).andReturn()

        assertThat(result.response.status).isEqualTo(401)
        assertThat(result.response.contentAsString)
            .contains("\"code\":\"BAD_CREDENTIALS\"")
            .doesNotContain("accessToken")
        assertThat(result.response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty()
    }

    private fun signUp(email: String, password: String, passwordConfirm: String): MvcResult =
        mockMvc.perform(
            post("/signup")
                .withCsrfToken()
                .param("email", email)
                .param("password", password)
                .param("passwordConfirm", passwordConfirm)
        ).andReturn()

    private fun logIn(email: String, password: String): MvcResult =
        mockMvc.perform(post("/login").withCsrfToken().param("email", email).param("password", password)).andReturn()

    /** 가입 화면이 [status] 로 다시 그려지고, 안내와 입력한 이메일이 남고, 비밀번호는 다시 채워지지 않는다. */
    private fun assertSignUpRejected(result: MvcResult, status: Int, message: String) {
        assertThat(result.response.status).isEqualTo(status)
        assertThat(result.response.contentAsString)
            .contains(message)
            .contains("action=\"/signup\"")
            .contains("value=\"$EMAIL\"")
            .doesNotContain("password\" value=")
        assertNoLoginCookies(result)
    }

    // 이 테스트들이 Redis 에 남긴 리프레시 키 전부다. tearDown 이 매번 비우므로 한 테스트의 것만 보인다.
    private fun refreshKeys(): Set<String> = redisTemplate.keys("${RefreshTokenStore.KEY_PREFIX}*")

    private fun refreshKey(refresh: Cookie): String =
        RefreshTokenStore.keyOf(requireNotNull(jwtClaim(refresh.value, "jti")))

    /** 값이 있는(지우는 것이 아닌) 로그인 쿠키. */
    private fun loginCookie(result: MvcResult, name: String): MockCookie {
        val cookie = result.response.getCookie(name) as? MockCookie
        assertThat(cookie).`as`(name).isNotNull()
        assertThat(cookie!!.value).`as`(name).isNotBlank()
        assertThat(cookie.maxAge).`as`(name).isPositive()
        return cookie
    }

    private fun assertNoLoginCookies(result: MvcResult) {
        assertThat(result.response.getCookie(AuthCookies.ACCESS)).isNull()
        assertThat(result.response.getCookie(AuthCookies.REFRESH)).isNull()
    }

    private fun assertLoginCookiesCleared(result: MvcResult) {
        listOf(AuthCookies.ACCESS, AuthCookies.REFRESH).forEach { name ->
            val cookie = result.response.getCookie(name)
            assertThat(cookie).`as`(name).isNotNull()
            assertThat(cookie!!.value).`as`(name).isEmpty()
            assertThat(cookie.maxAge).`as`(name).isZero()
            assertThat(cookie.path).`as`(name).isEqualTo("/")
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun redisProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerRedis(registry)
        }

        private const val EMAIL_PREFIX = "flow-"
        private const val EMAIL = "${EMAIL_PREFIX}alice@test.local"
        private const val PASSWORD = "correct-horse-battery"
    }
}
