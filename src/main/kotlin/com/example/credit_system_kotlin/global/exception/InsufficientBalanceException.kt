package com.example.credit_system_kotlin.global.exception

class InsufficientBalanceException(balance: Long, required: Long) :
    BusinessException("잔액이 부족합니다. balance=$balance, required=$required")
