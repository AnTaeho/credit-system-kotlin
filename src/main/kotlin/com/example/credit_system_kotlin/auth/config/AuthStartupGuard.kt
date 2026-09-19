package com.example.credit_system_kotlin.auth.config

import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * 운영(prod)에서 문을 열어 두는 설정 두 가지를 기동 단계에서 막는다.
 *
 * - 개발 로그인: 헤더 한 줄로 허용 목록의 아무나 될 수 있다. 운영에 켜져 있으면 구글 로그인이 무의미하다.
 * - 빈 허용 목록: 아무도 못 들어오는 것 자체는 안전하지만, 거의 확실히 환경변수를 빠뜨린 것이다.
 *   조용히 떠서 로그인만 전부 실패하는 것보다 부팅에서 죽는 편이 빨리 드러난다(application-prod.yml 과 같은 원칙).
 */
@Component
class AuthStartupGuard(
    authProperties: AuthProperties,
    environment: Environment
) {

    init {
        if (environment.acceptsProfiles(Profiles.of(PROD))) {
            check(!authProperties.devLogin.enabled) {
                "prod 프로필에서는 app.auth.dev-login.enabled 를 켤 수 없습니다. 개발 로그인은 로컬 전용입니다."
            }
            check(!authProperties.hasNoAllowedEmails()) {
                "prod 프로필에서는 app.auth.allowed-emails 가 비어 있을 수 없습니다."
            }
        }
    }

    companion object {
        private const val PROD = "prod"
    }
}
