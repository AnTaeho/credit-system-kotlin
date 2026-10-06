package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.user.dto.GrantRequest
import com.example.credit_system_kotlin.user.dto.GrantResponse
import com.example.credit_system_kotlin.user.service.UserService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자가 남에게 지급하는 API 라 여기만 대상 사용자 id 를 경로로 받는다. 다른 API 는 [CurrentUser] 만 쓴다.
 * ROLE_ADMIN 검사는 `SecurityConfig` 가 `/api/admin` 경로에 걸고, [CurrentUser] 는 지급한 사람을 남기는 데 쓴다.
 */
@RestController
@RequestMapping("/api/admin/users")
class AdminGrantApiController(
    private val userService: UserService
) {

    @PostMapping("/{userId}/grants")
    fun grant(
        currentUser: CurrentUser,
        @PathVariable userId: Long,
        @RequestBody request: GrantRequest
    ): GrantResponse = userService.grant(currentUser.userId, userId, request.idemKey, request.amount)
}
