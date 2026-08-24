package com.example.credit_system_kotlin.global

/**
 * 메시지가 항상 존재하는 도메인 예외의 공통 부모.
 * message를 non-null로 좁혀 GlobalExceptionHandler에서 널 처리 없이 응답에 사용할 수 있다.
 */
abstract class BusinessException(override val message: String) : RuntimeException(message)

class InvalidRequestException(message: String) : BusinessException(message)

class InsufficientBalanceException(balance: Long, required: Long) :
    BusinessException("잔액이 부족합니다. balance=$balance, required=$required")

class OrganizationNotFoundException(organizationId: Long) :
    BusinessException("존재하지 않는 organization: $organizationId")

class DuplicateRequestInProgressException :
    BusinessException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.")
