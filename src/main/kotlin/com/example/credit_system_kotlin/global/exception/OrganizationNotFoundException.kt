package com.example.credit_system_kotlin.global.exception

class OrganizationNotFoundException(organizationId: Long) :
    BusinessException("존재하지 않는 organization: $organizationId")
