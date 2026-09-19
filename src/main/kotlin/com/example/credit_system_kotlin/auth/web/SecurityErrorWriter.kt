package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.global.exception.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * 보안 필터에서 끝나는 요청의 응답을 컨트롤러 예외와 같은 [ErrorResponse] 모양으로 쓴다.
 * 필터는 `@RestControllerAdvice` 앞에서 돌기 때문에 GlobalExceptionHandler 가 닿지 않는다.
 */
@Component
class SecurityErrorWriter(
    private val jsonMapper: JsonMapper
) {

    fun write(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, ErrorResponse(code, message))
    }

    /** `/api` 아래 미인증 → 401. 로그인 페이지로 리다이렉트하지 않는다. */
    fun unauthenticatedEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.")
        }

    /** `/api` 아래 권한 부족·CSRF 토큰 없음 → 403. */
    fun forbiddenHandler(): AccessDeniedHandler =
        AccessDeniedHandler { _, response, _ ->
            write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.")
        }
}
