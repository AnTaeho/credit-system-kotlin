package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.job.concurrency.SharedContainers
import com.example.credit_system_kotlin.job.concurrency.runConcurrently
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.Collections

/**
 * 구글 로그인 문지기([AllowlistOidcUserService])를 실제 MySQL 위에서 돌린다.
 *
 * 토큰 교환과 서명 검증은 스프링의 일이라 건너뛰고, 검증이 끝난 ID 토큰을 받은 시점부터 본다.
 * 클라이언트 등록에 userinfo 주소를 비워 두면 OidcUserService 가 외부 호출 없이 ID 토큰만으로 사용자를 만든다.
 *
 * 동시 첫 로그인은 H2 가 아니라 MySQL 의 유니크 키 잠금 동작이 결과를 정하므로 Testcontainers 로 돌린다.
 */
@ActiveProfiles("test")
@SpringBootTest
class GoogleLoginProvisioningTest @Autowired constructor(
    private val oidcUserService: AllowlistOidcUserService,
    private val userRepository: UserRepository
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            SharedContainers.registerDatabase(registry, "google_login")
        }

        private const val USER_EMAIL = "user@test.local"
        private const val ADMIN_EMAIL = "admin@test.local"
    }

    @BeforeEach
    fun setUp() {
        userRepository.deleteAll()
    }

    @Test
    fun `허용 목록 밖 이메일은 로그인이 거부되고 사용자 행이 생기지 않는다`() {
        assertThatThrownBy { oidcUserService.loadUser(request("sub-stranger", "stranger@evil.test")) }
            .isInstanceOf(OAuth2AuthenticationException::class.java)

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `email_verified 가 false 면 허용 목록 안이어도 거부된다`() {
        assertThatThrownBy { oidcUserService.loadUser(request("sub-1", USER_EMAIL, emailVerified = false)) }
            .isInstanceOf(OAuth2AuthenticationException::class.java)

        assertThat(userRepository.count()).isZero()
    }

    @Test
    fun `첫 로그인은 잔액 0 인 사용자 한 행을 만든다`() {
        val principal = oidcUserService.loadUser(request("sub-1", USER_EMAIL, name = "안태호")) as AppOidcUser

        val users = userRepository.findAll()
        assertThat(users).hasSize(1)
        val created = users.single()
        assertThat(created.persistedId).isEqualTo(principal.userId)
        assertThat(created.googleSub).isEqualTo("sub-1")
        assertThat(created.email).isEqualTo(USER_EMAIL)
        assertThat(created.name).isEqualTo("안태호")
        assertThat(created.balance).isZero()
    }

    @Test
    fun `두 번째 로그인은 같은 행을 다시 쓴다`() {
        val first = oidcUserService.loadUser(request("sub-1", USER_EMAIL)) as AppOidcUser
        val second = oidcUserService.loadUser(request("sub-1", USER_EMAIL)) as AppOidcUser

        assertThat(second.userId).isEqualTo(first.userId)
        assertThat(userRepository.count()).isEqualTo(1)
    }

    @Test
    fun `구글 계정의 이메일이 바뀌면 sub 로 같은 행을 찾아 이메일을 갱신한다`() {
        val first = oidcUserService.loadUser(request("sub-1", USER_EMAIL)) as AppOidcUser
        // 대문자로 와도 같은 허용 목록 항목(admin@test.local)으로 본다
        val second = oidcUserService.loadUser(request("sub-1", "Admin@Test.Local")) as AppOidcUser

        assertThat(second.userId).isEqualTo(first.userId)
        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(userRepository.findById(first.userId).orElseThrow().email).isEqualTo(ADMIN_EMAIL)
    }

    @Test
    fun `개발 로그인으로 먼저 만들어진 행에는 구글 계정을 연결한다`() {
        val devCreated = userRepository.save(User("user", 700L, email = USER_EMAIL))

        val principal = oidcUserService.loadUser(request("sub-1", USER_EMAIL)) as AppOidcUser

        assertThat(principal.userId).isEqualTo(devCreated.persistedId)
        assertThat(userRepository.count()).isEqualTo(1)
        assertThat(userRepository.findById(devCreated.persistedId).orElseThrow().googleSub).isEqualTo("sub-1")
    }

    @Test
    fun `이메일이 같은데 이미 다른 구글 계정이 묶인 행이면 로그인이 거부되고 기존 행의 sub 는 그대로다`() {
        val existing = userRepository.save(User("user", 700L, email = USER_EMAIL, googleSub = "sub-original"))

        assertThatThrownBy { oidcUserService.loadUser(request("sub-other", USER_EMAIL)) }
            .isInstanceOf(OAuth2AuthenticationException::class.java)
            .satisfies({ e ->
                assertThat((e as OAuth2AuthenticationException).error.errorCode).isEqualTo("access_denied")
            })

        assertThat(userRepository.count()).isEqualTo(1)
        val found = userRepository.findById(existing.persistedId).orElseThrow()
        assertThat(found.googleSub).isEqualTo("sub-original")
        assertThat(found.email).isEqualTo(USER_EMAIL)
        assertThat(found.balance).isEqualTo(700L)
    }

    @Test
    fun `허용 목록이면 ROLE_USER 만, 운영자 목록이면 ROLE_ADMIN 이 더해진다`() {
        val user = oidcUserService.loadUser(request("sub-user", USER_EMAIL))
        val admin = oidcUserService.loadUser(request("sub-admin", ADMIN_EMAIL))

        assertThat(user.authorities.map { it.authority }).containsExactly(ROLE_USER)
        assertThat(admin.authorities.map { it.authority }).containsExactlyInAnyOrder(ROLE_USER, ROLE_ADMIN)
    }

    @Test
    fun `같은 계정의 동시 첫 로그인 N 개는 사용자 한 행으로 수렴한다`() {
        val threads = 10
        val userIds = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        runConcurrently(threads) {
            try {
                userIds.add((oidcUserService.loadUser(request("sub-race", USER_EMAIL)) as AppOidcUser).userId)
            } catch (e: Exception) {
                failures.add(e)
            }
        }

        assertThat(failures).isEmpty()
        assertThat(userIds).hasSize(threads)
        assertThat(userIds.toSet()).hasSize(1)
        assertThat(userRepository.count()).isEqualTo(1)
    }

    private fun request(
        sub: String,
        email: String,
        emailVerified: Boolean = true,
        name: String = "tester"
    ): OidcUserRequest {
        val now = Instant.now()
        val idToken = OidcIdToken(
            "id-token",
            now,
            now.plusSeconds(300),
            mapOf(
                IdTokenClaimNames.SUB to sub,
                IdTokenClaimNames.ISS to "https://accounts.google.com",
                IdTokenClaimNames.AUD to listOf("test-client-id"),
                "email" to email,
                "email_verified" to emailVerified,
                "name" to name
            )
        )
        val accessToken = OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "access-token", now, now.plusSeconds(300),
            setOf("openid", "email", "profile")
        )
        return OidcUserRequest(googleRegistration(), accessToken, idToken)
    }

    /** userInfoUri 를 일부러 비운다. 채우면 OidcUserService 가 구글 userinfo 를 호출한다. */
    private fun googleRegistration(): ClientRegistration =
        ClientRegistration.withRegistrationId("google")
            .clientId("test-client-id")
            .clientSecret("test-client-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid", "email", "profile")
            .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
            .tokenUri("https://www.googleapis.com/oauth2/v4/token")
            .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .build()
}
