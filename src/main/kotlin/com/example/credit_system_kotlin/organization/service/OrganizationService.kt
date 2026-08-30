package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(OrganizationService::class.java)

@Service
class OrganizationService(
    private val organizationFinder: OrganizationFinder
) {

    @Transactional(readOnly = true)
    fun getBalance(organizationId: Long): BalanceResponse {
        val organization = organizationFinder.getOrThrow(organizationId)
        return BalanceResponse(organization.balance)
    }

    @Transactional
    fun charge(organizationId: Long, amount: Long): ChargeResponse {
        validateRequest(amount)

        val organization = organizationFinder.getOrThrow(organizationId)
        organization.charge(amount)
        log.info("충전 완료: organizationId={}, amount={}", organizationId, amount)

        return ChargeResponse(organization.balance)
    }

    private fun validateRequest(amount: Long) {
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
