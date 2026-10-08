package com.example.credit_system_kotlin.web.dto

data class LoginRequest(
    val email: String? = null,
    val password: String? = null
) {
    override fun toString(): String = "LoginRequest(email=***, password=***)"
}
