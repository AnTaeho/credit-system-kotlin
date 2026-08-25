package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import com.example.credit_system_kotlin.organization.repository.getOrThrow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrganizationQueryService(private val organizationRepository: OrganizationRepository) {

    @Transactional(readOnly = true)
    fun getBalance(organizationId: Long): BalanceResponse {
        val organization = organizationRepository.getOrThrow(organizationId)
        return BalanceResponse(organization.balance)
    }
}
