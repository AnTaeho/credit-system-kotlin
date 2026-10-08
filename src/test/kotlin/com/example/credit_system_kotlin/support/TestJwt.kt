package com.example.credit_system_kotlin.support

import java.util.Base64

// JWT 의 가운데 토막(페이로드)을 base64url 로 풀어 JSON 문자열로 돌려준다. 서명은 보지 않는다.
fun jwtPayload(token: String): String =
    String(Base64.getUrlDecoder().decode(token.split(".")[1]), Charsets.UTF_8)

// 페이로드 JSON 에서 문자열 클레임 하나를 꺼낸다. 토큰을 그대로 넘겨도 된다.
fun jwtClaim(tokenOrPayload: String, name: String): String? {
    val payload = if (tokenOrPayload.startsWith("{")) tokenOrPayload else jwtPayload(tokenOrPayload)
    return Regex("\"$name\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
}
