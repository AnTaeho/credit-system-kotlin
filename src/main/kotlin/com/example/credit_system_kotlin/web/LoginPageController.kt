package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.config.AuthProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 로그인 화면. 인증 없이 열린다(SecurityConfig 의 permitAll). 로그인한 사람이 누구인지 모르므로
 * [com.example.credit_system_kotlin.auth.CurrentUser] 를 받지 않는다.
 *
 * 돌아오는 경로별 안내:
 * - `?error`   구글 로그인 거부(허용 목록 밖 등, `access_denied`) 또는 개발 로그인 거부
 * - `?logout`  로그아웃 완료
 * - `?expired` 화면의 JS 가 세션 만료(401 등)를 보고 보낸 경우
 */
@Controller
class LoginPageController(
    private val authProperties: AuthProperties
) {

    @GetMapping("/login")
    fun login(
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false) logout: String?,
        @RequestParam(required = false) expired: String?,
        model: Model
    ): String {
        model.addAttribute("loginError", error != null)
        model.addAttribute("loggedOut", logout != null)
        model.addAttribute("expired", expired != null)
        model.addAttribute("devLoginEnabled", authProperties.devLogin.enabled)
        // 개발 로그인이 꺼져 있으면 허용 목록을 화면에 싣지 않는다.
        model.addAttribute(
            "devLoginEmails",
            if (authProperties.devLogin.enabled) authProperties.allowedEmails else emptyList()
        )
        return "login"
    }
}
