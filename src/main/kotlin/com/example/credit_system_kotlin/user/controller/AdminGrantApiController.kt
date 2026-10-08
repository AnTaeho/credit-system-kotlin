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
    ): GrantResponse = userService.grant(currentUser.userId, userId, request)
}
