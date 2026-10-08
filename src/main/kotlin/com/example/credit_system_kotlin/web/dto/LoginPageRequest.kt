package com.example.credit_system_kotlin.web.dto

data class LoginPageRequest(
    val logout: String? = null,
    val expired: String? = null
)
