package com.example.credit_system_kotlin.job.generation.stub

import com.example.credit_system_kotlin.job.generation.GenerationException

/**
 * 프롬프트 본문은 메시지에 담지 않는다. 예외 메시지는 로그·응답으로 번져 나가고,
 * 프롬프트는 사용자가 쓴 내용이다. 어떤 요청이었는지는 jobId 로 따라간다(MDC).
 */
class StubGenerationException(prompt: String) :
    GenerationException("이미지 생성 stub 실패: promptChars=${prompt.length}")
