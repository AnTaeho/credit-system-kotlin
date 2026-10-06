package com.example.credit_system_kotlin.auth

/** 컨트롤러가 요청한 사람에 대해 아는 전부. 토큰이 헤더로 왔는지 쿠키로 왔는지는 여기까지 오지 않는다. */
data class CurrentUser(
    val userId: Long,
    val admin: Boolean
)
