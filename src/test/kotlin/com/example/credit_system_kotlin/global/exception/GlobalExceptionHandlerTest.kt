package com.example.credit_system_kotlin.global.exception

import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import java.sql.SQLException

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `잔액부족 예외는 409와 코드를 반환한다`() {
        val response = handler.handleInsufficientBalance(InsufficientBalanceException(50, 100))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("INSUFFICIENT_BALANCE")
    }

    @Test
    fun `중복처리중 예외는 409와 코드를 반환한다`() {
        val response = handler.handleDuplicateInProgress(DuplicateRequestInProgressException())

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("DUPLICATE_IN_PROGRESS")
    }

    @Test
    fun `유니크 제약 위반은 중복 처리중으로 번역된다`() {
        val uniqueViolation = ConstraintViolationException(
            "constraint violated", SQLException("Duplicate entry"),
            ConstraintViolationException.ConstraintKind.UNIQUE, "uk_idempotency_org_key"
        )

        val response = handler.handleDataIntegrityViolation(
            DataIntegrityViolationException("constraint violated", uniqueViolation)
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("DUPLICATE_IN_PROGRESS")
    }

    @Test
    fun `무관한 무결성 위반은 500과 별도 코드로 분리된다`() {
        val notNullViolation = ConstraintViolationException(
            "constraint violated", SQLException("Column 'prompt' cannot be null"),
            ConstraintViolationException.ConstraintKind.NOT_NULL, "prompt"
        )

        val response = handler.handleDataIntegrityViolation(
            DataIntegrityViolationException("constraint violated", notNullViolation)
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.code).isEqualTo("DATA_INTEGRITY_VIOLATION")
    }

    @Test
    fun `잘못된 요청은 400과 코드를 반환한다`() {
        val response = handler.handleInvalidRequest(InvalidRequestException("잘못된 요청"))

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo("INVALID_REQUEST")
        assertThat(response.body!!.message).isEqualTo("잘못된 요청")
    }

    @Test
    fun `존재하지 않는 조직 예외는 404와 코드를 반환한다`() {
        val response = handler.handleOrganizationNotFound(OrganizationNotFoundException(1L))

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.code).isEqualTo("ORGANIZATION_NOT_FOUND")
        assertThat(response.body!!.message).isEqualTo("존재하지 않는 organization: 1")
    }
}
