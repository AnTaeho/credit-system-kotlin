package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.user.domain.UserRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

interface AuthenticatedUser {
    val userId: Long
}

data class TokenUser(override val userId: Long) : AuthenticatedUser

const val ROLE_USER = "ROLE_USER"
const val ROLE_ADMIN = "ROLE_ADMIN"

fun authoritiesFor(role: UserRole): List<GrantedAuthority> =
    when (role) {
        UserRole.ADMIN -> listOf(SimpleGrantedAuthority(ROLE_USER), SimpleGrantedAuthority(ROLE_ADMIN))
        UserRole.USER -> listOf(SimpleGrantedAuthority(ROLE_USER))
    }
