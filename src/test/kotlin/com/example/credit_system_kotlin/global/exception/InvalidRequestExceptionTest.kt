package com.example.credit_system_kotlin.global.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

// 응답으로 나가는 문장을 글자 그대로 고정한다.
class InvalidRequestExceptionTest {

    @Test
    fun `이메일 문장`() {
        assertThat(InvalidRequestException.emailRequired().message).isEqualTo("이메일은 필수입니다.")
        assertThat(InvalidRequestException.emailTooLong(255).message).isEqualTo("이메일은 255자를 초과할 수 없습니다.")
        assertThat(InvalidRequestException.emailMalformed().message).isEqualTo("이메일 형식이 올바르지 않습니다.")
    }

    @Test
    fun `비밀번호 문장`() {
        assertThat(InvalidRequestException.passwordTooShort(8).message).isEqualTo("비밀번호는 8자 이상이어야 합니다.")
        assertThat(InvalidRequestException.passwordTooLong(72).message)
            .isEqualTo("비밀번호가 너무 깁니다. 영문 기준 72자까지 쓸 수 있습니다.")
    }

    @Test
    fun `amount 문장은 상한에 천 단위 쉼표를 넣는다`() {
        assertThat(InvalidRequestException.amountNotPositive().message).isEqualTo("amount는 0보다 커야 합니다.")
        assertThat(InvalidRequestException.amountTooLarge(1_000_000L).message)
            .isEqualTo("amount는 1,000,000을 초과할 수 없습니다.")
    }

    @Test
    fun `prompt 문장`() {
        assertThat(InvalidRequestException.promptRequired().message).isEqualTo("prompt는 필수입니다.")
        assertThat(InvalidRequestException.promptTooLong(1000).message).isEqualTo("prompt는 1000자를 초과할 수 없습니다.")
    }

    @Test
    fun `cursor 문장은 받은 값을 끝에 붙인다`() {
        assertThat(InvalidRequestException.cursorNotNumber("abc").message).isEqualTo("cursor 는 숫자여야 합니다: abc")
        assertThat(InvalidRequestException.cursorNotPositive(-1L).message).isEqualTo("cursor 는 양수여야 합니다: -1")
    }

    @Test
    fun `size 문장은 받은 값을 끝에 붙인다`() {
        assertThat(InvalidRequestException.sizeNotNumber("abc").message).isEqualTo("size 는 숫자여야 합니다: abc")
        assertThat(InvalidRequestException.sizeOutOfRange(100, 101).message)
            .isEqualTo("size 는 1 이상 100 이하여야 합니다: 101")
    }

    @Test
    fun `idemKey 문장`() {
        assertThat(InvalidRequestException.idemKeyRequired().message).isEqualTo("idemKey는 필수입니다.")
        assertThat(InvalidRequestException.idemKeyTooLong(100).message).isEqualTo("idemKey는 100자를 초과할 수 없습니다.")
    }
}
