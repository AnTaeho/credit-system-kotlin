package com.example.credit_system_kotlin.global.exception

class IdempotencyKeyReusedException :
    BusinessException("같은 요청 번호로 다른 내용을 보낼 수 없습니다. 다시 시도해주세요.")
