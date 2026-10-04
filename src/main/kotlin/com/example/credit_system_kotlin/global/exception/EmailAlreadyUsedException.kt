package com.example.credit_system_kotlin.global.exception

/** 이미 가입된 이메일로 다시 가입하려 했다. 동시 가입으로 유니크 제약에 걸린 경우도 여기로 모은다. */
class EmailAlreadyUsedException : BusinessException("이미 가입된 이메일입니다.")
