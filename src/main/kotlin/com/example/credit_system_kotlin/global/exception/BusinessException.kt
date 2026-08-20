package com.example.credit_system_kotlin.global.exception

/**
 * 메시지가 항상 존재하는 도메인 예외의 공통 부모.
 * message를 non-null로 좁혀 GlobalExceptionHandler에서 널 처리 없이 응답에 사용할 수 있다.
 */
abstract class BusinessException(override val message: String) : RuntimeException(message)
