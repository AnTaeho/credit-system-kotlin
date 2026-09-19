package com.example.credit_system_kotlin.global.exception

/** 없는 job 과 남의 job 을 구분하지 않는다. 둘 다 이 예외로 404 가 된다. */
class JobNotFoundException(jobId: Long) :
    BusinessException("존재하지 않는 job: $jobId")
