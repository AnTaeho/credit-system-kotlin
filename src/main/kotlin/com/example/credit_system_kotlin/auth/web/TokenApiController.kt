package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.global.exception.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class TokenRequest(val email: String, val password: String) {
    // 비밀번호가 로그나 예외 메시지로 새지 않게 가린다.
    override fun toString(): String = "TokenRequest(email=***, password=***)"
}

data class TokenResponse(val accessToken: String, val expiresInSeconds: Long)

/**
 * 스크립트·curl 용 토큰 발급. 쿠키를 심지 않고 리프레시도 내지 않으니 만료되면 다시 부른다.
 * 로그인 전 요청이 부르는 곳이라 `/api` 밖에 둔다.
 */
@RestController
class TokenApiController(
    private val accountService: AccountService,
    private val accessTokenService: AccessTokenService,
    private val jwtProperties: JwtProperties
) {

    /** 이메일이나 비밀번호가 틀리면 어느 쪽인지 가리지 않고 401 을 준다. */
    @PostMapping(PATH)
    fun token(@RequestBody request: TokenRequest): ResponseEntity<Any> {
        val user = accountService.authenticate(request.email, request.password)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse("BAD_CREDENTIALS", "이메일 또는 비밀번호가 맞지 않습니다."))
        val accessToken = accessTokenService.issue(user.persistedId, user.role)
        return ResponseEntity.ok(TokenResponse(accessToken, jwtProperties.accessTtl.seconds))
    }

    companion object {
        const val PATH = "/auth/token"
    }
}
