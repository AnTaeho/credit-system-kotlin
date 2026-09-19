package com.example.credit_system_kotlin.global.exception

class UserNotFoundException(userId: Long) :
    BusinessException("존재하지 않는 user: $userId")
