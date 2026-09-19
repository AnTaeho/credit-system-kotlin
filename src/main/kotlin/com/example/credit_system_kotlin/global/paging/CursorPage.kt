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
        /**
         * [fetched] 는 `size + 1` 개까지 조회한 결과다. 한 개가 넘쳤다면 다음 페이지가 있다는 뜻이고,
         * 그 한 개는 버린 채 이번 페이지 마지막 item 의 id 를 커서로 준다. 빈 마지막 페이지 요청을
         * 만들지 않으려고 count 대신 이렇게 판단한다.
         */
        fun <E, T> of(fetched: List<E>, size: Int, idOf: (E) -> Long, map: (E) -> T): CursorPage<T> {
            val hasNext = fetched.size > size
            val page = if (hasNext) fetched.take(size) else fetched
            return CursorPage(page.map(map), if (hasNext) idOf(page.last()) else null)
        }
    }
}
