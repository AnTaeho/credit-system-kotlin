package com.example.credit_system_kotlin.auth

/**
 * 컨트롤러가 아는 "지금 요청한 사람"의 전부.
 *
 * 토큰이 헤더로 왔는지 쿠키로 왔는지, 방금 갱신됐는지는 여기에 드러나지 않는다.
 * 그 구분은 보안 계층과 [com.example.credit_system_kotlin.auth.web.CurrentUserArgumentResolver] 만 안다.
 */
data class CurrentUser(
    val userId: Long,
    val admin: Boolean
)
