package com.example.credit_system_kotlin.organization

import com.example.credit_system_kotlin.global.InvalidRequestException
import com.example.credit_system_kotlin.global.OrganizationNotFoundException
import com.example.credit_system_kotlin.global.validateIdemKey
import com.example.credit_system_kotlin.ledger.LedgerEntry
import com.example.credit_system_kotlin.ledger.LedgerRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(ChargeService::class.java)

@Service
class ChargeService(
    private val organizationRepository: OrganizationRepository,
    private val ledgerRepository: LedgerRepository
) {

    @Transactional
    fun charge(organizationId: Long, idemKey: String, amount: Long): ChargeResponse {
        validateRequest(idemKey, amount)

        val existing = ledgerRepository.findByOrganizationIdAndIdemKey(organizationId, idemKey)
        if (existing != null) {
            val balance = organizationRepository.findByIdOrNull(organizationId)?.balance
                ?: throw OrganizationNotFoundException(organizationId)
            log.info("중복 충전 요청 감지: organizationId={}, idemKey={}", organizationId, idemKey)
            return ChargeResponse(balance, true)
        }

        val updated = organizationRepository.addBalance(organizationId, amount, Instant.now())
        if (updated != 1) {
            throw OrganizationNotFoundException(organizationId)
        }

        ledgerRepository.save(LedgerEntry.charge(organizationId, idemKey, amount))
        log.info("충전 완료: organizationId={}, amount={}", organizationId, amount)

        val balance = organizationRepository.findByIdOrNull(organizationId)?.balance
            ?: throw OrganizationNotFoundException(organizationId)
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
