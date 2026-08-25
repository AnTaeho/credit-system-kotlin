package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.organization.dto.BalanceResponse
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrganizationQueryService(private val organizationRepository: OrganizationRepository) {

    @Transactional(readOnly = true)
    fun getBalance(organizationId: Long): BalanceResponse {
        val organization = organizationRepository.findByIdOrNull(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
        return BalanceResponse(organization.balance)
    }
}
