package com.example.credit_system_kotlin.global.paging

import com.example.credit_system_kotlin.global.exception.InvalidRequestException

class CursorRequest private constructor(
    val cursor: Long?,
    val size: Int
) {
    val fetchSize: Int
        get() = size + 1

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun of(cursor: String?, size: String?): CursorRequest =
            CursorRequest(parseCursor(cursor), parseSize(size))

        private fun parseCursor(raw: String?): Long? {
            if (raw == null) return null
            val value = raw.toLongOrNull() ?: throw InvalidRequestException.cursorNotNumber(raw)
            if (value <= 0) throw InvalidRequestException.cursorNotPositive(value)
            return value
        }

        private fun parseSize(raw: String?): Int {
            if (raw == null) return DEFAULT_SIZE
            val value = raw.toIntOrNull() ?: throw InvalidRequestException.sizeNotNumber(raw)
            if (value !in 1..MAX_SIZE) {
                throw InvalidRequestException.sizeOutOfRange(MAX_SIZE, value)
            }
            return value
        }
    }
}
