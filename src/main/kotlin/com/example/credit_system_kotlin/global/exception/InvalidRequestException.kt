package com.example.credit_system_kotlin.global.exception

import java.util.Locale

// 잘못된 요청의 문장은 여기서 만든다. 던지는 쪽은 경우에 맞는 팩토리에 값만 넘긴다.
class InvalidRequestException private constructor(message: String) : BusinessException(message) {

    companion object {
        fun emailRequired() = InvalidRequestException("이메일은 필수입니다.")

        fun emailTooLong(max: Int) = InvalidRequestException("이메일은 ${max}자를 초과할 수 없습니다.")

        fun emailMalformed() = InvalidRequestException("이메일 형식이 올바르지 않습니다.")

        fun passwordTooShort(min: Int) = InvalidRequestException("비밀번호는 ${min}자 이상이어야 합니다.")

        fun passwordTooLong(maxBytes: Int) =
            InvalidRequestException("비밀번호가 너무 깁니다. 영문 기준 ${maxBytes}자까지 쓸 수 있습니다.")

        fun amountNotPositive() = InvalidRequestException("amount는 0보다 커야 합니다.")

        fun amountTooLarge(max: Long) =
            InvalidRequestException("amount는 %,d을 초과할 수 없습니다.".format(Locale.ROOT, max))

        fun promptRequired() = InvalidRequestException("prompt는 필수입니다.")

        fun promptTooLong(max: Int) = InvalidRequestException("prompt는 ${max}자를 초과할 수 없습니다.")

        fun cursorNotNumber(raw: String) = InvalidRequestException("cursor 는 숫자여야 합니다: $raw")

        fun cursorNotPositive(value: Long) = InvalidRequestException("cursor 는 양수여야 합니다: $value")

        fun sizeNotNumber(raw: String) = InvalidRequestException("size 는 숫자여야 합니다: $raw")

        fun sizeOutOfRange(max: Int, value: Int) =
            InvalidRequestException("size 는 1 이상 $max 이하여야 합니다: $value")

        fun idemKeyRequired() = InvalidRequestException("idemKey는 필수입니다.")

        fun idemKeyTooLong(max: Int) = InvalidRequestException("idemKey는 ${max}자를 초과할 수 없습니다.")
    }
}
