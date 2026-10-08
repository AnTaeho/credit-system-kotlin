package com.example.credit_system_kotlin.global.paging

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CursorRequestTest {

    @Test
    fun `size 는 1 과 100 을 받는다`() {
        assertThat(CursorRequest.of(null, "1").size).isEqualTo(1)
        assertThat(CursorRequest.of(null, "100").size).isEqualTo(100)
    }

    @Test
    fun `size 가 0 이면 거절한다`() {
        assertThatThrownBy { CursorRequest.of(null, "0") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("size 는 1 이상 100 이하여야 합니다: 0")
    }

    @Test
    fun `size 가 101 이면 거절한다`() {
        assertThatThrownBy { CursorRequest.of(null, "101") }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("size 는 1 이상 100 이하여야 합니다: 101")
    }

    @Test
    fun `cursor 는 1 을 받는다`() {
        assertThat(CursorRequest.of("1", null).cursor).isEqualTo(1L)
    }

    @Test
    fun `cursor 가 0 이면 거절한다`() {
        assertThatThrownBy { CursorRequest.of("0", null) }
            .isInstanceOf(InvalidRequestException::class.java)
            .hasMessage("cursor 는 양수여야 합니다: 0")
    }
}
