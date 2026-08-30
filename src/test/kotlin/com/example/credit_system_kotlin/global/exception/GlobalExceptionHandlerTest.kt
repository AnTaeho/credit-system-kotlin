package com.example.credit_system_kotlin.global.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `잔액부족 예외는 409와 코드를 반환한다`() {
        val response = handler.handleInsufficientBalance(InsufficientBalanceException(50, 100))

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!.code).isEqualTo("INSUFFICIENT_BALANCE")
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
