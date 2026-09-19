package com.example.credit_system_kotlin.auth.login

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser

/** 인증 주체가 무엇이든 우리 사용자 행의 id 를 꺼낼 수 있게 하는 공통 면. */
interface AuthenticatedUser {
    val userId: Long
}

/** 구글 로그인으로 들어온 주체. 세션에 저장되므로 직렬화 가능해야 한다(DefaultOidcUser 가 이미 그렇다). */
class AppOidcUser(
    override val userId: Long,
    authorities: Collection<GrantedAuthority>,
    idToken: OidcIdToken,
    userInfo: OidcUserInfo?
) : DefaultOidcUser(authorities, idToken, userInfo), AuthenticatedUser

/** 개발 로그인 헤더로 들어온 주체. 요청 하나에만 살고 세션에 저장되지 않는다. */
data class DevLoginUser(
    override val userId: Long,
    val email: String
) : AuthenticatedUser

const val ROLE_USER = "ROLE_USER"
const val ROLE_ADMIN = "ROLE_ADMIN"

fun authoritiesFor(admin: Boolean): List<GrantedAuthority> =
    if (admin) {
        listOf(SimpleGrantedAuthority(ROLE_USER), SimpleGrantedAuthority(ROLE_ADMIN))
    } else {
        listOf(SimpleGrantedAuthority(ROLE_USER))
    }
