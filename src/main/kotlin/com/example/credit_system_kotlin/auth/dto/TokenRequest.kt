package com.example.credit_system_kotlin.auth.dto

data class TokenRequest(val email: String, val password: String) {
    override fun toString(): String = "TokenRequest(email=***, password=***)"
}
