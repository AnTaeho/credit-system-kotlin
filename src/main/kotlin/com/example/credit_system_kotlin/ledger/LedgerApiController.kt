package com.example.credit_system_kotlin.ledger

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger")
class LedgerApiController(
    private val ledgerQueryService: LedgerQueryService
) {

    @GetMapping
    fun list(@RequestHeader("X-Organization-Id") organizationId: Long): List<LedgerResponse> =
        ledgerQueryService.findByOrganization(organizationId)
}
