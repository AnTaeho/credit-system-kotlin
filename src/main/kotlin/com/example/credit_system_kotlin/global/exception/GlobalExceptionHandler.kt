package com.example.credit_system_kotlin.global.exception

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.event.DefenseTriggered
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

/** 예외를 `ErrorResponse` JSON 으로 바꾼다. 업무 규칙 위반은 409, 입력 오류는 400, 없는 대상은 404 다. */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val eventPublisher: ApplicationEventPublisher
) {

    @ExceptionHandler(InsufficientBalanceException::class)
    fun handleInsufficientBalance(e: InsufficientBalanceException): ResponseEntity<ErrorResponse> =
        conflict("INSUFFICIENT_BALANCE", e.message)

    @ExceptionHandler(DuplicateRequestInProgressException::class)
    fun handleDuplicateInProgress(e: DuplicateRequestInProgressException): ResponseEntity<ErrorResponse> =
        conflict("DUPLICATE_IN_PROGRESS", e.message)

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun handleIdempotencyKeyReused(e: IdempotencyKeyReusedException): ResponseEntity<ErrorResponse> =
        conflict("IDEMPOTENCY_KEY_REUSED", e.message)

    @ExceptionHandler(EmailAlreadyUsedException::class)
    fun handleEmailAlreadyUsed(e: EmailAlreadyUsedException): ResponseEntity<ErrorResponse> =
        conflict("EMAIL_ALREADY_USED", e.message)

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(e: InvalidRequestException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", e.message))

    // JSON 문법이 깨지는 등 문제 발생.
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("요청 본문 해석 실패: {}", e.message)
        return ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "요청 본문의 형식이 올바르지 않습니다."))
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest().body(ErrorResponse("INVALID_REQUEST", "${e.name} 값의 형식이 올바르지 않습니다."))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(e: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        if (isUniqueConstraintViolation(e)) {
            eventPublisher.publishEvent(DefenseTriggered(DefensePoint.IDEM_KEY, DefenseOutcome.DB_UNIQUE))
            return conflict("DUPLICATE_IN_PROGRESS", "동일한 요청이 동시에 처리 중입니다. 잠시 후 다시 시도해주세요.")
        }

        log.error("무결성 제약 위반: {}", e.message, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("DATA_INTEGRITY_VIOLATION", "요청을 처리할 수 없습니다."))
    }

    /** 스프링이 감싼 예외의 cause 사슬에서 Hibernate 의 제약 위반을 찾아 종류가 UNIQUE 인지 본다. */
    private fun isUniqueConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .firstOrNull()
            ?.kind == ConstraintViolationException.ConstraintKind.UNIQUE

    @ExceptionHandler(UserNotFoundException::class)
    fun handleUserNotFound(e: UserNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("business exception: code=USER_NOT_FOUND, message={}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("USER_NOT_FOUND", e.message))
    }

    @ExceptionHandler(JobNotFoundException::class)
    fun handleJobNotFound(e: JobNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("business exception: code=JOB_NOT_FOUND, message={}", e.message)
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("JOB_NOT_FOUND", e.message))
    }

    private fun conflict(code: String, message: String): ResponseEntity<ErrorResponse> {
        log.info("business exception: code={}, message={}", code, message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(code, message))
    }
}
