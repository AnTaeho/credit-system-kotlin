package com.example.credit_system_kotlin.ledger

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerQueryService(private val ledgerRepository: LedgerRepository) {

    @Transactional(readOnly = true)
    fun findByOrganization(organizationId: Long): List<LedgerResponse> =
        ledgerRepository.findByOrganizationIdOrderByIdDesc(organizationId)
            .map(LedgerResponse::from)
}
