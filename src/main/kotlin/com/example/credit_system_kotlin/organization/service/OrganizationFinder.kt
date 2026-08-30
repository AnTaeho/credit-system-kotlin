package com.example.credit_system_kotlin.organization.service

import com.example.credit_system_kotlin.global.exception.OrganizationNotFoundException
import com.example.credit_system_kotlin.organization.domain.Organization
import com.example.credit_system_kotlin.organization.repository.OrganizationRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class OrganizationFinder(
    private val organizationRepository: OrganizationRepository
) {

    fun getOrThrow(organizationId: Long): Organization =
        organizationRepository.findByIdOrNull(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
}
