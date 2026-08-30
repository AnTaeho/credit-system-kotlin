package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.dto.ChargeResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val log = LoggerFactory.getLogger(OrganizationService::class.java)

@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository
) {

    @Transactional(readOnly = true)
    fun getBalance(organizationId: Long): BalanceResponse {
        val organization = organizationRepository.findByIdOrNull(organizationId)
            ?: error("조직을 찾을 수 없습니다: organizationId=$organizationId")
        return BalanceResponse(organization.balance)
    }

    @Transactional
    fun charge(organizationId: Long, amount: Long): ChargeResponse {
        val organization = getOrganization(organizationId)
        organization.charge(amount)
        log.info("충전 완료: organizationId={}, amount={}", organizationId, amount)

        return ChargeResponse(organization.balance)
    }

    private fun getOrganization(organizationId: Long): Organization =
        organizationRepository.findByIdOrNull(organizationId)
            ?: error("조직을 찾을 수 없습니다: organizationId=$organizationId")
}
