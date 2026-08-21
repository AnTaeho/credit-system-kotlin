package com.example.credit_system_kotlin.organization.controller

import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeRequest
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.service.ChargeService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/organizations")
class OrganizationApiController(
    private val organizationRepository: OrganizationRepository,
    private val chargeService: ChargeService
) {

    @GetMapping("/me/balance")
    fun myBalance(@RequestHeader("X-Organization-Id") organizationId: Long): BalanceResponse {
        val organization = organizationRepository.findByIdOrNull(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
        return BalanceResponse(organization.balance)
    }

    @PostMapping("/me/charge")
    fun charge(
        @RequestHeader("X-Organization-Id") organizationId: Long,
        @RequestBody request: ChargeRequest
    ): ChargeResponse = chargeService.charge(organizationId, request.idemKey, request.amount)
}
