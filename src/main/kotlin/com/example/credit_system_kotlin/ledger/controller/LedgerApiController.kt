package com.example.credit_system_kotlin.ledger.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.service.LedgerQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger")
class LedgerApiController(
    private val ledgerQueryService: LedgerQueryService
) {

    @GetMapping
    fun list(currentUser: CurrentUser): List<LedgerResponse> =
        ledgerQueryService.findByUser(currentUser.userId)
}
