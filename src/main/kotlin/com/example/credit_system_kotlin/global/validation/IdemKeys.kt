package com.example.credit_system_kotlin.global.validation

import com.example.credit_system_kotlin.global.exception.InvalidRequestException

const val IDEM_KEY_MAX_LENGTH = 100

/** 운영자 지급과 생성 요청이 같이 쓴다. 길이 상한은 원장 `idemKey` 컬럼 길이와 같다. */
fun validateIdemKey(idemKey: String) {
    if (idemKey.isBlank()) {
        throw InvalidRequestException("idemKey는 필수입니다.")
    }
    if (idemKey.length > IDEM_KEY_MAX_LENGTH) {
        throw InvalidRequestException("idemKey는 ${IDEM_KEY_MAX_LENGTH}자를 초과할 수 없습니다.")
    }
}
