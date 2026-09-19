package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.maskEmail
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(AllowlistOidcUserService::class.java)

/**
 * 구글 로그인의 문지기. 토큰 검증은 스프링이 끝낸 뒤라 여기서는 "들여보낼 사람인가"만 본다.
 *
 * 1. `email_verified == true` — 확인 안 된 이메일은 누구 것인지 알 수 없다
 * 2. 이메일이 허용 목록에 있다
 * 3. 통과하면 `sub` 로 사용자 행을 찾거나 만든다
 *
 * 어느 하나라도 아니면 [OAuth2AuthenticationException] 으로 로그인 자체를 거부한다. 사용자 행은 만들지 않는다.
 */
@Component
class AllowlistOidcUserService(
    private val authProperties: AuthProperties,
    private val provisioner: UserAccountProvisioner
) : OidcUserService() {

    override fun loadUser(userRequest: OidcUserRequest): OidcUser = admit(super.loadUser(userRequest))

    private fun admit(oidcUser: OidcUser): AppOidcUser {
        val email = oidcUser.email
        if (email.isNullOrBlank()) {
            throw denied("이메일 없음", email)
        }
        if (oidcUser.emailVerified != true) {
            throw denied("email_verified 가 아님", email)
        }
        if (!authProperties.isAllowed(email)) {
            throw denied("허용 목록 밖", email)
        }

        val userId = try {
            provisioner.provisionGoogleUser(
                googleSub = requireNotNull(oidcUser.subject) { "sub 가 없는 ID 토큰입니다." },
                email = email,
                name = oidcUser.fullName ?: email.substringBefore('@')
            )
        } catch (e: GoogleAccountConflictException) {
            throw denied("이미 다른 구글 계정이 연결된 이메일", email)
        }
        return AppOidcUser(
            userId = userId,
            authorities = authoritiesFor(authProperties.isAdmin(email)),
            idToken = oidcUser.idToken,
            userInfo = oidcUser.userInfo
        )
    }

    private fun denied(reason: String, email: String?): OAuth2AuthenticationException {
        log.warn("구글 로그인 거부: reason={}, email={}", reason, maskEmail(email))
        return OAuth2AuthenticationException(OAuth2Error(ACCESS_DENIED, "로그인이 허용되지 않은 계정입니다.", null))
    }

    companion object {
        private const val ACCESS_DENIED = "access_denied"
    }
}
