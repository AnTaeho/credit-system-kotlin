package com.example.credit_system_kotlin.ledger.service

import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerQueryService(private val ledgerRepository: LedgerRepository) {

    @Transactional(readOnly = true)
    fun findByUser(userId: Long, request: CursorRequest): CursorPage<LedgerResponse> {
        val limit = PageRequest.of(0, request.fetchSize)
        val fetched = request.cursor
            ?.let { ledgerRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, it, limit) }
            ?: ledgerRepository.findByUserIdOrderByIdDesc(userId, limit)
        return CursorPage.of(fetched, request.size, LedgerEntry::persistedId, LedgerResponse::from)
    }
}
