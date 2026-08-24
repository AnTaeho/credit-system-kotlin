package com.example.credit_system_kotlin.global

import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientBalanceException::class)
    fun handleInsufficientBalance(e: InsufficientBalanceException): ResponseEntity<ErrorResponse> =
        conflict("INSUFFICIENT_BALANCE", e.message)

    @ExceptionHandler(DuplicateRequestInProgressException::class)
    fun handleDuplicateInProgress(e: DuplicateRequestInProgressException): ResponseEntity<ErrorResponse> =
        conflict("DUPLICATE_IN_PROGRESS", e.message)

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(e: InvalidRequestException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("요청 본문 해석 실패: {}", e.message)
        return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "요청 본문의 형식이 올바르지 않습니다."))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        if (isUniqueConstraintViolation(e)) {
            return conflict("DUPLICATE_IN_PROGRESS", "동일한 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해주세요.")
        }

        log.error("무결성 제약 위반: {}", e.message, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("DATA_INTEGRITY_VIOLATION", "요청을 처리할 수 없습니다."))
    }

    private fun isUniqueConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.kind == ConstraintViolationException.ConstraintKind.UNIQUE

    @ExceptionHandler(OrganizationNotFoundException::class)
    fun handleOrganizationNotFound(e: OrganizationNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("business exception: code=ORGANIZATION_NOT_FOUND, message={}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("ORGANIZATION_NOT_FOUND", e.message))
    }

    private fun conflict(code: String, message: String): ResponseEntity<ErrorResponse> {
        log.info("business exception: code={}, message={}", code, message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(code, message))
    }
}

data class ErrorResponse(
    val code: String,
    val message: String
)
