package com.example.credit_system_kotlin.auth

data class CurrentUser(
    val userId: Long,
    val admin: Boolean
)
