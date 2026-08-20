package com.example.credit_system_kotlin.ledger.controller

import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger")
class LedgerApiController(
    private val ledgerRepository: LedgerRepository
) {

    @GetMapping
    fun list(@RequestHeader("X-Organization-Id") organizationId: Long): List<LedgerResponse> =
        ledgerRepository.findByOrganizationIdOrderByIdDesc(organizationId)
            .map(LedgerResponse::from)
}
