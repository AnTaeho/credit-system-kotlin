package com.example.credit_system_kotlin.global.exception

import org.slf4j.LoggerFactory
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

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(e: InvalidRequestException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("요청 본문 해석 실패: {}", e.message)
        return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "요청 본문의 형식이 올바르지 않습니다."))
    }

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
