package com.example.credit_system_kotlin.global.exception

class StubGenerationException(prompt: String) :
    RuntimeException("이미지 생성 stub 실패: prompt=$prompt")