package com.example.credit_system_kotlin.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * 개발 로그인이 꺼진 기동(운영과 같은 모양). 속성 조합이 달라 컨텍스트를 하나 더 띄운다.
 * 화면 폼도, `/dev-login` 핸들러도, 헤더 로그인도 없어야 한다.
 */
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = ["app.auth.dev-login.enabled=false"])
class DevSessionLoginDisabledTest @Autowired constructor(
    private val mockMvc: MockMvc
) {

    @Test
    fun `로그인 화면에 개발 로그인 폼이 없다`() {
        val result = mockMvc.perform(get("/login")).andReturn()

        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.contentAsString)
            .contains("구글로 로그인")
            .doesNotContain("/dev-login")
            .doesNotContain("user@test.local")
    }

    @Test
    fun `dev-login 은 404 이고 로그인되지 않는다`() {
        val result = mockMvc.perform(post("/dev-login").param("email", "user@test.local").with(csrf())).andReturn()

        assertThat(result.response.status).isEqualTo(404)
        assertThat(result.request.getSession(false)?.getAttribute("SPRING_SECURITY_CONTEXT")).isNull()
    }

    @Test
    fun `헤더 개발 로그인도 동작하지 않는다`() {
        val result = mockMvc.perform(get("/api/users/me/balance").header("X-Dev-User", "user@test.local")).andReturn()

        assertThat(result.response.status).isEqualTo(401)
    }
}
