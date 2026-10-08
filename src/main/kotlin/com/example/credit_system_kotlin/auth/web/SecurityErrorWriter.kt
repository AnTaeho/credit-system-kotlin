package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.global.exception.ErrorResponse
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

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

    fun unauthenticatedEntryPoint(): AuthenticationEntryPoint =
        AuthenticationEntryPoint { _, response, _ ->
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.")
        }

    fun forbiddenHandler(): AccessDeniedHandler =
        AccessDeniedHandler { _, response, _ ->
            write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다.")
        }
}
