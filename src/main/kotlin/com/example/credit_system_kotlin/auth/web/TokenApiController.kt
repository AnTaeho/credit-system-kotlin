package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.account.AccountService
import com.example.credit_system_kotlin.auth.config.JwtProperties
import com.example.credit_system_kotlin.auth.dto.TokenRequest
import com.example.credit_system_kotlin.auth.dto.TokenResponse
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.global.exception.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

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
