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
 *
 * 예외는 [ADMIN_USER_ID_EXCEPTIONS] 에 이름으로 적은 운영자 API 뿐이다. 운영자가 남에게 지급하는 것이
 * 그 API 의 뜻이라 대상 사용자 id 를 경로에서 받는다. 예외는 `/api/admin` 아래(ROLE_ADMIN 전용)에만
 * 둘 수 있고, 실제 핸들러와 어긋나면 이 테스트가 깨진다.
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
        val checked = apiHandlers.filterNot { (pattern, _) -> pattern in ADMIN_USER_ID_EXCEPTIONS }
        val suspicious = checked.flatMap { (pattern, method) ->
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
    fun `사용자 식별자를 요청에서 받는 예외는 운영자 경로에만 있다`() {
        assertThat(ADMIN_USER_ID_EXCEPTIONS).allMatch { it.startsWith("/api/admin/") }
    }

    @Test
    fun `예외 목록의 경로는 실제 핸들러로 존재하고 경로 변수 userId 로만 받는다`() {
        val exceptionHandlers = apiHandlers.filter { (pattern, _) -> pattern in ADMIN_USER_ID_EXCEPTIONS }

        assertThat(exceptionHandlers.map { it.first }).containsExactlyInAnyOrderElementsOf(ADMIN_USER_ID_EXCEPTIONS)
        exceptionHandlers.forEach { (pattern, method) ->
            val fromRequest = method.methodParameters
                .filter {
                    it.hasParameterAnnotation(RequestHeader::class.java) ||
                        it.hasParameterAnnotation(RequestParam::class.java) ||
                        it.hasParameterAnnotation(PathVariable::class.java)
                }
                .filter { looksLikeUserId(it.parameterName) || looksLikeUserId(annotatedName(it)) }
            assertThat(fromRequest).`as`(pattern).singleElement()
                .matches { it.hasParameterAnnotation(PathVariable::class.java) }
        }
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

    companion object {
        /**
         * 대상 사용자 id 를 요청 경로에서 받아도 되는 API. 9-B 원칙의 의도적 예외이며 ROLE_ADMIN 으로만 열린다.
         * 여기에 더하는 것은 "남의 계정을 건드리는 운영자 API 를 하나 더 연다"는 결정이다.
         */
        private val ADMIN_USER_ID_EXCEPTIONS = setOf("/api/admin/users/{userId}/grants")
    }
}
