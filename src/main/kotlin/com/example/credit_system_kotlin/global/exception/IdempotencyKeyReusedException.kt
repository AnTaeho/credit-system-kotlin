package com.example.credit_system_kotlin.global.exception

/** 이미 쓴 멱등키로 내용이 다른 요청이 들어왔다. 기존 job 을 돌려주면 다른 요청의 결과를 주게 된다. */
class IdempotencyKeyReusedException :
    BusinessException("같은 요청 번호로 다른 내용을 보낼 수 없습니다. 다시 시도해주세요.")
