package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.user.dto.BalanceResponse
import com.example.credit_system_kotlin.user.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserApiController(
    private val userService: UserService
) {

    @GetMapping("/me/balance")
    fun myBalance(currentUser: CurrentUser): BalanceResponse =
        userService.getBalance(currentUser.userId)
}
