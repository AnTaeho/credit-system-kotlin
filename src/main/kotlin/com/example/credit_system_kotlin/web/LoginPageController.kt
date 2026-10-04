package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.login.AuthenticatedUser
import com.example.credit_system_kotlin.auth.web.TokenLogin
import com.example.credit_system_kotlin.global.exception.EmailAlreadyUsedException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView

/**
 * 로그인과 가입 화면. 인증 없이 열린다(SecurityConfig 의 permitAll). 아직 누구인지 모르는 요청이 오는 곳이라
 * [com.example.credit_system_kotlin.auth.CurrentUser] 를 받지 않는다.
 *
 * 성공하면 로그인 쿠키를 심고 홈으로 보낸다. 실패하면 **같은 화면을 오류 상태 코드로 다시 그린다.** 리다이렉트하지
 * 않으므로 입력한 이메일이 남고, 비밀번호는 다시 채우지 않는다.
 *
 * 로그인 화면이 보이는 안내:
 * - `?logout`  로그아웃 완료
 * - `?expired` 화면의 JS 가 로그인 만료(401)를 보고 보낸 경우
 */
@Controller
class LoginPageController(
    private val accountService: AccountService,
    private val tokenLogin: TokenLogin
) {

    @GetMapping("/login")
    fun loginPage(
        @RequestParam(required = false) logout: String?,
        @RequestParam(required = false) expired: String?
    ): ModelAndView {
        if (alreadyLoggedIn()) {
            return ModelAndView(REDIRECT_HOME)
        }
        return ModelAndView(LOGIN_VIEW, mapOf("loggedOut" to (logout != null), "expired" to (expired != null)))
    }

    /** 왜 틀렸는지(없는 이메일인지 비밀번호인지)는 화면에도 알리지 않는다. */
    @PostMapping("/login")
    fun login(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) password: String?,
        response: HttpServletResponse
    ): ModelAndView {
        val user = accountService.authenticate(email.orEmpty(), password.orEmpty())
            ?: return ModelAndView(
                LOGIN_VIEW,
                mapOf("loginError" to true, "email" to email.orEmpty()),
                HttpStatus.UNAUTHORIZED
            )
        tokenLogin.logIn(user, response)
        return ModelAndView(REDIRECT_HOME)
    }

    @GetMapping("/signup")
    fun signUpPage(): String = SIGNUP_VIEW

    /** 가입하면 바로 로그인한 상태가 된다. 가입은 항상 일반 사용자다. */
    @PostMapping("/signup")
    fun signUp(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) password: String?,
        @RequestParam(required = false) passwordConfirm: String?,
        response: HttpServletResponse
    ): ModelAndView {
        if (password.orEmpty() != passwordConfirm.orEmpty()) {
            return signUpFailed(HttpStatus.BAD_REQUEST, "비밀번호가 서로 다릅니다.", email)
        }
        val user = try {
            accountService.signUp(email.orEmpty(), password.orEmpty())
        } catch (e: InvalidRequestException) {
            return signUpFailed(HttpStatus.BAD_REQUEST, e.message, email)
        } catch (e: EmailAlreadyUsedException) {
            return signUpFailed(HttpStatus.CONFLICT, e.message, email)
        }
        tokenLogin.logIn(user, response)
        return ModelAndView(REDIRECT_HOME)
    }

    private fun signUpFailed(status: HttpStatus, message: String, email: String?): ModelAndView =
        ModelAndView(SIGNUP_VIEW, mapOf("signupError" to message, "email" to email.orEmpty()), status)

    private fun alreadyLoggedIn(): Boolean =
        SecurityContextHolder.getContext().authentication?.principal is AuthenticatedUser

    companion object {
        private const val LOGIN_VIEW = "login"
        private const val SIGNUP_VIEW = "signup"
        private const val REDIRECT_HOME = "redirect:/"
    }
}
