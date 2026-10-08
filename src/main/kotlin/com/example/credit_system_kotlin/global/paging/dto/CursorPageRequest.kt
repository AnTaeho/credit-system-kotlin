package com.example.credit_system_kotlin.global.paging.dto

import com.example.credit_system_kotlin.global.paging.CursorRequest

data class CursorPageRequest(
    val cursor: String? = null,
    val size: String? = null
) {
    fun toCursorRequest(): CursorRequest = CursorRequest.of(cursor, size)
}
