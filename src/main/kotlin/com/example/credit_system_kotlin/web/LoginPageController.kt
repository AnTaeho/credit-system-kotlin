package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.dto.SignUpRequest
import com.example.credit_system_kotlin.auth.login.AuthenticatedUser
import com.example.credit_system_kotlin.auth.web.TokenLogin
import com.example.credit_system_kotlin.global.exception.EmailAlreadyUsedException
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.web.dto.LoginPageRequest
import com.example.credit_system_kotlin.web.dto.LoginRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.ModelAndView

@Controller
class LoginPageController(
    private val accountService: AccountService,
    private val tokenLogin: TokenLogin
) {

    @GetMapping("/login")
    fun loginPage(
        @ModelAttribute request: LoginPageRequest
    ): ModelAndView {
        if (alreadyLoggedIn()) {
            return ModelAndView(REDIRECT_HOME)
        }
        return ModelAndView(
            LOGIN_VIEW,
            mapOf("loggedOut" to (request.logout != null), "expired" to (request.expired != null))
        )
    }

    @PostMapping("/login")
    fun login(
        @ModelAttribute request: LoginRequest,
        response: HttpServletResponse
    ): ModelAndView {
        val user = accountService.authenticate(request.email.orEmpty(), request.password.orEmpty())
            ?: return ModelAndView(
                LOGIN_VIEW,
                mapOf("loginError" to true, "email" to request.email.orEmpty()),
                HttpStatus.UNAUTHORIZED
            )
        tokenLogin.logIn(user, response)
        return ModelAndView(REDIRECT_HOME)
    }

    @GetMapping("/signup")
    fun signUpPage(): String = SIGNUP_VIEW

    @PostMapping("/signup")
    fun signUp(
        @ModelAttribute request: SignUpRequest,
        response: HttpServletResponse
    ): ModelAndView {
        if (request.password.orEmpty() != request.passwordConfirm.orEmpty()) {
            return signUpFailed(HttpStatus.BAD_REQUEST, "비밀번호가 서로 다릅니다.", request.email)
        }
        val user = try {
            accountService.signUp(request)
        } catch (e: InvalidRequestException) {
            return signUpFailed(HttpStatus.BAD_REQUEST, e.message, request.email)
        } catch (e: EmailAlreadyUsedException) {
            return signUpFailed(HttpStatus.CONFLICT, e.message, request.email)
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
