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
 * 스크립트·curl 용 토큰 발급. 이메일과 비밀번호를 주면 액세스 JWT 를 본문으로 돌려준다.
 *
 *     TOKEN=$(curl -s localhost:8080/auth/token -H 'Content-Type: application/json' \
 *       -d '{"email":"dev@local.test","password":"..."}' | jq -r .accessToken)
 *     curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/users/me/balance
 *
 * 쿠키를 심지 않고 리프레시 토큰도 내지 않는다. 만료되면 다시 부른다. 브라우저는 `/login` 을 쓴다.
 * 아직 아무도 아닌 요청이 부르는 곳이라 `/api` 밖에 둔다. `/api` 아래 핸들러는 모두 로그인한 사용자를 받는다.
 */
@RestController
class TokenApiController(
    private val accountService: AccountService,
    private val accessTokenService: AccessTokenService,
    private val jwtProperties: JwtProperties
) {

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
