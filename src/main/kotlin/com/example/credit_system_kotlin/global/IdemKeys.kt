package com.example.credit_system_kotlin.global

const val IDEM_KEY_MAX_LENGTH = 100

fun validateIdemKey(idemKey: String) {
    if (idemKey.isBlank()) {
        throw InvalidRequestException("idemKey는 필수입니다.")
    }
    if (idemKey.length > IDEM_KEY_MAX_LENGTH) {
        throw InvalidRequestException("idemKey는 ${IDEM_KEY_MAX_LENGTH}자를 초과할 수 없습니다.")
    }
}
