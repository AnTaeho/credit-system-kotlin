package com.example.credit_system_kotlin.global.paging

import com.example.credit_system_kotlin.global.exception.InvalidRequestException

/**
 * 커서 페이징 요청. [cursor] 는 배타적 상한 id(`id < cursor`)이고 첫 페이지는 null 이다.
 *
 * 쿼리 파라미터를 문자열로 받아 여기서 해석한다. 숫자가 아닌 값도 다른 검증 실패와 같은
 * `INVALID_REQUEST` 응답으로 돌려주기 위해서다.
 */
class CursorRequest private constructor(
    val cursor: Long?,
    val size: Int
) {
    /** 다음 페이지가 있는지 알기 위해 한 개 더 조회한다. */
    val fetchSize: Int
        get() = size + 1

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun of(cursor: String?, size: String?): CursorRequest {
            val parsedCursor = cursor?.let {
                val value = it.toLongOrNull() ?: throw InvalidRequestException("cursor 는 숫자여야 합니다: $it")
                if (value <= 0) throw InvalidRequestException("cursor 는 양수여야 합니다: $value")
                value
            }
            val parsedSize = size?.let {
                val value = it.toIntOrNull() ?: throw InvalidRequestException("size 는 숫자여야 합니다: $it")
                if (value !in 1..MAX_SIZE) {
                    throw InvalidRequestException("size 는 1 이상 $MAX_SIZE 이하여야 합니다: $value")
                }
                value
            } ?: DEFAULT_SIZE
            return CursorRequest(parsedCursor, parsedSize)
        }
    }
}
