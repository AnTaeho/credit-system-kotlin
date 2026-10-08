package com.example.credit_system_kotlin.global.exception

class JobNotFoundException(jobId: Long) :
    BusinessException("존재하지 않는 job: $jobId")
