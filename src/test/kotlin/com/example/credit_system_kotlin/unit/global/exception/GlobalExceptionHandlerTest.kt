package com.example.credit_system_kotlin.unit.global.exception

import com.example.credit_system_kotlin.global.event.DefenseOutcome
import com.example.credit_system_kotlin.global.event.DefensePoint
import com.example.credit_system_kotlin.global.exception.GlobalExceptionHandler
import com.example.credit_system_kotlin.support.RecordingEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import java.sql.SQLException

class GlobalExceptionHandlerTest {

    private val eventPublisher = RecordingEventPublisher()

    private val handler = GlobalExceptionHandler(eventPublisher)

    @Test
    fun `유니크 제약 위반은 중복 처리중으로 번역된다`() {
        val uniqueViolation = ConstraintViolationException(
            "constraint violated", SQLException("Duplicate entry"),
            ConstraintViolationException.ConstraintKind.UNIQUE, "uk_idempotency_user_key"
        )

        val response = handler.handleDataIntegrityViolation(
            DataIntegrityViolationException("constraint violated", uniqueViolation)
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("DUPLICATE_IN_PROGRESS")
        assertThat(eventPublisher.countOf(DefensePoint.IDEM_KEY, DefenseOutcome.DB_UNIQUE)).isEqualTo(1)
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
        assertThat(eventPublisher.defenseEvents()).isEmpty()
    }
}
