package com.example.credit_system_kotlin.global.paging

data class CursorPage<T>(
    val items: List<T>,
    val nextCursor: Long?
) {
    companion object {
        fun <E, T> of(fetched: List<E>, size: Int, idOf: (E) -> Long, map: (E) -> T): CursorPage<T> {
            val hasNext = fetched.size > size
            val page = if (hasNext) fetched.take(size) else fetched
            return CursorPage(page.map(map), if (hasNext) idOf(page.last()) else null)
        }
    }
}
