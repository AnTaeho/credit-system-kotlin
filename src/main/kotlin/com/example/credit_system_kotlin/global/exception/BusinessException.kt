package com.example.credit_system_kotlin.global.exception

/**
 * 메시지가 항상 존재하는 도메인 예외의 공통 부모.
 */
abstract class BusinessException(override val message: String) : RuntimeException(message)
