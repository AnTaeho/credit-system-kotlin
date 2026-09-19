package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

private val log = LoggerFactory.getLogger(DevLoginAuthenticator::class.java)

/**
 * 개발 로그인 두 갈래(헤더 [DevLoginFilter], 세션 [DevSessionLoginController])가 같이 쓰는 판정.
 *
 * 구글을 거치지 않을 뿐 허용 목록 검사와 권한 부여는 구글 로그인과 같다. 인증을 어디에 거는지
 * (요청 하나 / 세션)는 부르는 쪽이 정한다.
 *
 * 빈으로 등록하지 않는다. 개발 로그인이 켜졌을 때만 SecurityConfig·세션 로그인 컨트롤러가 만든다.
 */
class DevLoginAuthenticator(
    private val authProperties: AuthProperties,
    private val provisioner: UserAccountProvisioner
) {

    /** 허용 목록 밖이면 null. 이때 사용자 행은 만들지 않는다. */
    fun authenticate(rawEmail: String, channel: String): Authentication? {
        val email = normalizeEmail(rawEmail)
        if (!authProperties.isAllowed(email)) {
            log.warn("개발 로그인 거부: 허용 목록 밖, channel={}, email={}", channel, maskEmail(email))
            return null
        }
        val userId = provisioner.provisionDevUser(email)
        return UsernamePasswordAuthenticationToken.authenticated(
            DevLoginUser(userId, email),
            null,
            authoritiesFor(authProperties.isAdmin(email))
        )
    }
}
