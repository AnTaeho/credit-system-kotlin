package com.example.credit_system_kotlin.global.exception

class DuplicateRequestInProgressException :
    BusinessException("동일한 요청이 처리 중입니다. 잠시 후 다시 시도해주세요.")
