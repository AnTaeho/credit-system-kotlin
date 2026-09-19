package com.example.credit_system_kotlin.user.dto

/** 대상 사용자는 본문이 아니라 경로에서 온다. 본문에 사용자 식별자를 두지 않는다. */
data class GrantRequest(val idemKey: String, val amount: Long)
