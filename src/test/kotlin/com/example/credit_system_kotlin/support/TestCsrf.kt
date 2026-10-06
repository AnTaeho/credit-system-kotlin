package com.example.credit_system_kotlin.support

import jakarta.servlet.http.Cookie
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

private val secureRandom = SecureRandom()

/**
 * 브라우저가 하듯 CSRF 토큰 쿠키(`XSRF-TOKEN`)와 `X-XSRF-TOKEN` 헤더를 한 쌍으로 싣는다.
 * spring-security-test 의 `csrf()` 는 그 컨텍스트의 CSRF 저장소를 세션 저장소로 바꿔 끼워 뒤따르는 테스트가 순서에 따라 깨지므로 쓰지 않는다.
 */
fun MockHttpServletRequestBuilder.withCsrfToken(): MockHttpServletRequestBuilder {
    val token = UUID.randomUUID().toString()
    return cookie(Cookie("XSRF-TOKEN", token)).header("X-XSRF-TOKEN", maskCsrfToken(token))
}

/**
 * 스프링 시큐리티가 화면에 내주는 모양(`XorCsrfTokenRequestAttributeHandler`)으로 토큰을 가린다.
 * 같은 길이의 난수와 XOR 한 뒤 `난수 + 결과` 를 base64url 로 적는다. 서버는 거꾸로 풀어 원래 토큰을 얻는다.
 */
private fun maskCsrfToken(token: String): String {
    val tokenBytes = token.toByteArray(Charsets.UTF_8)
    val random = ByteArray(tokenBytes.size).also(secureRandom::nextBytes)
    val xored = ByteArray(tokenBytes.size) { (random[it].toInt() xor tokenBytes[it].toInt()).toByte() }
    return Base64.getUrlEncoder().encodeToString(random + xored)
}
