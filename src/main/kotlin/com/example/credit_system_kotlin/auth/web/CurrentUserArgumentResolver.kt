package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.auth.login.AuthenticatedUser
import com.example.credit_system_kotlin.auth.login.ROLE_ADMIN
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): CurrentUser {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AuthenticatedUser
            ?: throw AuthenticationCredentialsNotFoundException("인증된 사용자가 없습니다.")
        val admin = authentication.authorities.any { it.authority == ROLE_ADMIN }
        return CurrentUser(principal.userId, admin)
    }
}

@Configuration
class CurrentUserWebConfig : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserArgumentResolver())
    }
}
