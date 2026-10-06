package com.example.credit_system_kotlin.auth.config

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * prod 에서 `Secure` 없는 로그인 쿠키와 저장소에 공개된 로컬 서명 키를 기동 단계에서 막는다.
 * 앞의 것은 평문 HTTP 에 토큰이 실리고, 뒤의 것은 누구나 운영자 토큰을 만들 수 있다.
 */
@Component
class AuthStartupGuard(
    authProperties: AuthProperties,
    jwtProperties: JwtProperties,
    environment: Environment
) {

    init {
        if (environment.acceptsProfiles(Profiles.of(PROD))) {
            check(authProperties.cookieSecure) {
                "prod 프로필에서는 app.auth.cookie-secure 가 true 여야 합니다. 로그인 쿠키가 평문 HTTP 로 나갑니다."
            }
            check(jwtProperties.secret != JwtProperties.LOCAL_DEFAULT_SECRET) {
                "prod 프로필에서는 app.auth.jwt.secret 에 로컬 기본값을 쓸 수 없습니다. 저장소에 공개된 키입니다."
            }
        }
    }

    companion object {
        private const val PROD = "prod"
    }
}
