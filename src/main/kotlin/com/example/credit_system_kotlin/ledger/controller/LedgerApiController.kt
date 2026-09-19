package com.example.credit_system_kotlin.ledger.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.service.LedgerQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/ledger")
class LedgerApiController(
    private val ledgerQueryService: LedgerQueryService
) {

    /** 페이지 파라미터는 문자열로 받는다. 해석과 검증은 [CursorRequest] 가 한다. */
    @GetMapping
    fun list(
        currentUser: CurrentUser,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: String?
    ): CursorPage<LedgerResponse> =
        ledgerQueryService.findByUser(currentUser.userId, CursorRequest.of(cursor, size))
}
