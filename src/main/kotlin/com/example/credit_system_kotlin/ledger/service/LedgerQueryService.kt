package com.example.credit_system_kotlin.ledger.service

import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.dto.LedgerResponse
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 원장을 읽기만 한다. 쓰기는 잔액을 바꾸는 서비스가 같은 트랜잭션 안에서 직접 한다. */
@Service
class LedgerQueryService(private val ledgerRepository: LedgerRepository) {

    /** 최신순 한 페이지. 다음 페이지가 있는지 보려고 한 줄 더 읽고, 다음 커서는 [CursorPage] 가 정한다. */
    @Transactional(readOnly = true)
    fun findByUser(userId: Long, request: CursorRequest): CursorPage<LedgerResponse> {
        val limit = PageRequest.of(0, request.fetchSize)
        val fetched = request.cursor
            ?.let { ledgerRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, it, limit) }
            ?: ledgerRepository.findByUserIdOrderByIdDesc(userId, limit)
        return CursorPage.of(fetched, request.size, LedgerEntry::persistedId, LedgerResponse::from)
    }
}
