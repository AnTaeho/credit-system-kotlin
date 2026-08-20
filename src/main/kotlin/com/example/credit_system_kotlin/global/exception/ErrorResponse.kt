package com.example.credit_system_kotlin.global.exception

data class ErrorResponse(
    val code: String,
    val message: String
) {
}