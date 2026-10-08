package com.example.credit_system_kotlin.auth.dto

data class TokenResponse(val accessToken: String, val expiresInSeconds: Long)
