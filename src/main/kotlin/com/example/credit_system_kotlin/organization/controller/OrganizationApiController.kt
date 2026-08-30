package com.example.credit_system_kotlin.organization.controller

import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeRequest
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import com.example.credit_system_kotlin.organization.service.OrganizationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/organizations")
class OrganizationApiController(
    private val organizationService: OrganizationService
) {

    @GetMapping("/me/balance")
    fun myBalance(@RequestHeader("X-Organization-Id") organizationId: Long): BalanceResponse =
        organizationService.getBalance(organizationId)

    @PostMapping("/me/charge")
    fun charge(
        @RequestHeader("X-Organization-Id") organizationId: Long,
        @RequestBody request: ChargeRequest
    ): ChargeResponse = organizationService.charge(organizationId, request.amount)
}
