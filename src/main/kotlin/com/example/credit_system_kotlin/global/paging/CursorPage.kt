package com.example.credit_system_kotlin.global.paging

/**
 * id 내림차순 커서 페이지. [nextCursor] 는 다음 페이지 요청에 그대로 넘기는 배타적 상한 id 이고,
 * 더 가져올 것이 없으면 null 이다.
 */
data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: Long?
) {
    companion object {
        /** [fetched] 는 `size + 1` 개까지 읽은 결과다. 한 개가 넘치면 다음 페이지가 있다고 보고 버려서 count 쿼리를 안 쓴다. */
        fun <E, T> of(fetched: List<E>, size: Int, idOf: (E) -> Long, map: (E) -> T): CursorPage<T> {
            val hasNext = fetched.size > size
            val page = if (hasNext) fetched.take(size) else fetched
            return CursorPage(page.map(map), if (hasNext) idOf(page.last()) else null)
        }
    }
}
