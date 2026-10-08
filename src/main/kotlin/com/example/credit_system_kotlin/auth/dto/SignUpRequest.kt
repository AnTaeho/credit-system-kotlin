package com.example.credit_system_kotlin.auth.dto

data class SignUpRequest(
    val email: String? = null,
    val password: String? = null,
    val passwordConfirm: String? = null
) {
    override fun toString(): String = "SignUpRequest(email=***, password=***, passwordConfirm=***)"
}
