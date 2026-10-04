package com.example.credit_system_kotlin.support

import jakarta.servlet.http.Cookie
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

private val secureRandom = SecureRandom()

/**
 * 브라우저가 하듯 CSRF 토큰을 싣는다. 토큰 쿠키(`XSRF-TOKEN`)와, 화면이 meta·hidden 으로 받아 돌려보내는 값
 * (`X-XSRF-TOKEN` 헤더)을 한 쌍으로 붙인다. 서버는 헤더의 값을 풀어 쿠키와 같은지 본다.
 *
 * **spring-security-test 의 `csrf()` 를 쓰지 않는다.** 그 후처리기는 한 번 쓰이는 순간 컨텍스트의 CSRF 필터가
 * 쥔 저장소를 세션 저장소로 바꿔 끼우고 되돌리지 않는다. 그 뒤로는 같은 컨텍스트의 모든 요청이 세션을 만들고
 * 토큰 쿠키를 받지 못해서, "세션이 생기지 않는다"와 "화면이 토큰 쿠키를 심는다"를 보는 테스트가 실행 순서에 따라 깨진다.
 * 이 함수는 운영과 같은 쿠키 저장소를 그대로 지난다.
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
