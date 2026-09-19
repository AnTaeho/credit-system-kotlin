package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.CurrentUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.MethodParameter
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * "남의 userId 로 접근할 길이 없다"를 컨트롤러 시그니처로 못 박는다.
 *
 * `/api` 아래 모든 핸들러는 사용자를 [CurrentUser](인증 주체)로만 받는다. 헤더·쿼리·경로·본문 어디에서도
 * 사용자 id 를 받지 않는다. 새 API 가 이 규칙을 어기면 이 테스트가 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ApiIdentitySourceTest @Autowired constructor(
    @Qualifier("requestMappingHandlerMapping")
    private val handlerMapping: RequestMappingHandlerMapping
) {

    private val apiHandlers: List<Pair<String, HandlerMethod>> by lazy {
        handlerMapping.handlerMethods.flatMap { (info, method) ->
            info.patternValues.filter { it.startsWith("/api") }.map { it to method }
        }
    }

    @Test
    fun `api 핸들러가 존재한다`() {
        assertThat(apiHandlers).isNotEmpty()
    }

    @Test
    fun `모든 api 핸들러는 CurrentUser 를 받는다`() {
        val missing = apiHandlers
            .filter { (_, method) -> method.methodParameters.none { it.parameterType == CurrentUser::class.java } }
            .map { (pattern, method) -> "$pattern ${method.method.name}" }

        assertThat(missing).isEmpty()
    }

    @Test
    fun `api 핸들러는 요청의 헤더·쿼리·경로에서 사용자 식별자를 받지 않는다`() {
        val suspicious = apiHandlers.flatMap { (pattern, method) ->
            method.methodParameters
                .filter {
                    it.hasParameterAnnotation(RequestHeader::class.java) ||
                        it.hasParameterAnnotation(RequestParam::class.java) ||
                        it.hasParameterAnnotation(PathVariable::class.java)
                }
                .filter { looksLikeUserId(it.parameterName) || looksLikeUserId(annotatedName(it)) }
                .map { "$pattern ${method.method.name}(${it.parameterName})" }
        }

        assertThat(suspicious).isEmpty()
    }

    @Test
    fun `api 요청 본문 타입에는 사용자 식별자 필드가 없다`() {
        val suspicious = apiHandlers.flatMap { (pattern, method) ->
            method.methodParameters
                .filter { it.hasParameterAnnotation(RequestBody::class.java) }
                .flatMap { param ->
                    param.parameterType.declaredFields.map { param.parameterType.simpleName to it.name }
                }
                .filter { (_, field) -> looksLikeUserId(field) }
                .map { (type, field) -> "$pattern $type.$field" }
        }

        assertThat(suspicious).isEmpty()
    }

    private fun annotatedName(param: MethodParameter): String? =
        param.getParameterAnnotation(RequestHeader::class.java)?.let { it.name.ifEmpty { it.value } }
            ?: param.getParameterAnnotation(RequestParam::class.java)?.let { it.name.ifEmpty { it.value } }
            ?: param.getParameterAnnotation(PathVariable::class.java)?.let { it.name.ifEmpty { it.value } }

    private fun looksLikeUserId(name: String?): Boolean {
        val normalized = name?.lowercase()?.replace("-", "")?.replace("_", "") ?: return false
        return listOf("userid", "organizationid", "orgid", "ownerid", "accountid").any { it in normalized }
    }
}
