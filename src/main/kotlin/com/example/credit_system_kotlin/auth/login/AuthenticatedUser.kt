package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.user.domain.UserRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** 인증 주체가 무엇이든 우리 사용자 행의 id 를 꺼낼 수 있게 하는 공통 면. */
interface AuthenticatedUser {
    val userId: Long
}

/**
 * 액세스 토큰으로 들어온 주체. 요청 하나에만 살고 어디에도 저장되지 않는다.
 * 토큰이 헤더로 왔는지 쿠키로 왔는지는 여기에 남기지 않는다.
 */
data class TokenUser(override val userId: Long) : AuthenticatedUser

const val ROLE_USER = "ROLE_USER"
const val ROLE_ADMIN = "ROLE_ADMIN"

/** 운영자도 일반 사용자가 하는 일은 다 한다. 그래서 운영자는 두 권한을 모두 갖는다. */
fun authoritiesFor(role: UserRole): List<GrantedAuthority> =
    when (role) {
        UserRole.ADMIN -> listOf(SimpleGrantedAuthority(ROLE_USER), SimpleGrantedAuthority(ROLE_ADMIN))
        UserRole.USER -> listOf(SimpleGrantedAuthority(ROLE_USER))
    }
