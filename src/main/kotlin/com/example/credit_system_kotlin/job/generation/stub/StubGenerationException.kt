package com.example.credit_system_kotlin.job.generation.stub

import com.example.credit_system_kotlin.job.generation.GenerationException

class StubGenerationException(prompt: String) :
    GenerationException("이미지 생성 stub 실패: prompt=$prompt")
