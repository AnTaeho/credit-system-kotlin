package com.example.credit_system_kotlin.auth.token

import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.user.domain.UserRole
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(AccessTokenService::class.java)

data class AccessPrincipal(val userId: Long, val role: UserRole)

@Service
class AccessTokenService(
    private val jwtCodec: JwtCodec,
    private val jwtProperties: JwtProperties
) {

    fun issue(userId: Long, role: UserRole): String =
        jwtCodec.encode(TokenType.ACCESS, userId, jwtProperties.accessTtl) { claim(ROLE_CLAIM, role.name) }

    fun verify(token: String): AccessPrincipal? = jwtCodec.decode(token, TokenType.ACCESS)?.let(::toPrincipal)

    private fun toPrincipal(jwt: Jwt): AccessPrincipal? {
        val userId = jwt.subject?.toLongOrNull()
        val role = UserRole.entries.firstOrNull { it.name == jwt.getClaimAsString(ROLE_CLAIM) }
        if (userId == null || role == null) {
            log.warn("서명은 유효하지만 sub·role 클레임이 올바르지 않은 액세스 토큰")
            return null
        }
        return AccessPrincipal(userId, role)
    }

    companion object {
        const val ROLE_CLAIM = "role"
    }
}
