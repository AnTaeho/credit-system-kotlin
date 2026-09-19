package com.example.credit_system_kotlin.global.exception

/** 속도 제한에 걸린 요청. [retryAfterSeconds] 는 다음 요청이 통과할 수 있을 때까지 남은 초(1 이상)다. */
class RateLimitedException(val retryAfterSeconds: Long) :
    BusinessException("요청이 너무 많습니다. ${retryAfterSeconds}초 후 다시 시도해주세요.")
