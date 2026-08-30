package com.example.credit_system_kotlin.organization.repository

import com.example.credit_system_kotlin.organization.domain.Organization
import org.springframework.data.jpa.repository.JpaRepository

interface OrganizationRepository : JpaRepository<Organization, Long>
