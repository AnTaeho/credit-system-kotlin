package com.example.credit_system_kotlin.global.validation

import com.example.credit_system_kotlin.global.exception.InvalidRequestException

const val IDEM_KEY_MAX_LENGTH = 100

fun validateIdemKey(idemKey: String) {
    if (idemKey.isBlank()) {
        throw InvalidRequestException.idemKeyRequired()
    }
    if (idemKey.length > IDEM_KEY_MAX_LENGTH) {
        throw InvalidRequestException.idemKeyTooLong(IDEM_KEY_MAX_LENGTH)
    }
}
