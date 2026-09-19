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
 * 운영자 지급. 실제 결제 충전이 붙기 전까지 잔액을 올리는 유일한 길이다.
 *
 * **의도적 예외.** 다른 모든 API 는 사용자를 인증 주체([CurrentUser])로만 받는다. 이 API 만 대상
 * 사용자 id 를 요청 경로에서 받는다 — 운영자가 남에게 지급하는 것이 이 API 의 뜻이기 때문이다.
 * 그래서 `/api/admin` 아래에만 두고, `SecurityConfig` 의 규칙대로 ROLE_ADMIN 만 들어온다.
 * [CurrentUser] 는 누가 지급했는지를 남기는 데 쓴다. 예외 목록은 `ApiIdentitySourceTest` 에 있다.
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
