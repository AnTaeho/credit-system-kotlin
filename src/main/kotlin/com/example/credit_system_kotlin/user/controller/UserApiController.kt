package com.example.credit_system_kotlin.user.controller

import com.example.credit_system_kotlin.user.dto.BalanceResponse
import com.example.credit_system_kotlin.user.dto.ChargeRequest
import com.example.credit_system_kotlin.user.dto.ChargeResponse
import com.example.credit_system_kotlin.user.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserApiController(
    private val userService: UserService
) {

    @GetMapping("/me/balance")
    fun myBalance(@RequestHeader("X-Organization-Id") userId: Long): BalanceResponse =
        userService.getBalance(userId)

    @PostMapping("/me/charge")
    fun charge(
        @RequestHeader("X-Organization-Id") userId: Long,
        @RequestBody request: ChargeRequest
    ): ChargeResponse = userService.charge(userId, request.idemKey, request.amount)
}
