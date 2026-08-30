package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.global.validation.validateIdemKey
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(OrganizationService::class.java)

@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository,
    private val organizationFinder: OrganizationFinder,
    private val ledgerRepository: LedgerRepository
) {

    @Transactional(readOnly = true)
    fun getBalance(organizationId: Long): BalanceResponse {
        val organization = organizationFinder.getOrThrow(organizationId)
        return BalanceResponse(organization.balance)
    }

    @Transactional
    fun charge(organizationId: Long, idemKey: String, amount: Long): ChargeResponse {
        validateRequest(idemKey, amount)

        val existing = ledgerRepository.findByOrganizationIdAndIdemKey(organizationId, idemKey)
        if (existing != null) {
            val balance = organizationFinder.getOrThrow(organizationId).balance
            log.info("중복 충전 요청 감지: organizationId={}, idemKey={}", organizationId, idemKey)
            return ChargeResponse(balance, true)
        }

        val updated = organizationRepository.addBalance(organizationId, amount, Instant.now())
        if (updated != 1) {
            throw OrganizationNotFoundException(organizationId)
        }

        ledgerRepository.save(LedgerEntry.charge(organizationId, idemKey, amount))
        log.info("충전 완료: organizationId={}, amount={}", organizationId, amount)

        val balance = organizationFinder.getOrThrow(organizationId).balance
        return ChargeResponse(balance, false)
    }

    private fun validateRequest(idemKey: String, amount: Long) {
        validateIdemKey(idemKey)
        if (amount <= 0) {
            throw InvalidRequestException("amount는 0보다 커야 합니다.")
        }
        if (amount > MAX_CHARGE_AMOUNT) {
            throw InvalidRequestException("amount는 1,000,000을 초과할 수 없습니다.")
        }
    }

    companion object {
        private const val MAX_CHARGE_AMOUNT = 1_000_000L
    }
}
