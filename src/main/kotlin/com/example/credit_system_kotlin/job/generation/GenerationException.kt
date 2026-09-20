package com.example.credit_system_kotlin.job.generation

/**
 * [GenerationClient] 가 "이번 시도는 결과를 못 냈다"고 알리는 예외의 공통 상위 타입.
 *
 * 워커는 이 타입 하나만 알면 되고, 어떤 구현이 왜 실패했는지(스텁의 확률 실패냐, 타임아웃이냐,
 * 나중에 붙을 API 오류냐)는 하위 타입이 구분한다. 덕분에 워커가 스텁 패키지를 import 하지 않는다.
 */
abstract class GenerationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * 외부 생성이 `app.generation.timeout-seconds` 안에 끝나지 않았다.
 *
 * 생성 실패와 **같은 경로**로 끝난다(markFailed → 회수·재시도 대상). 돈이 묶이지 않도록
 * "언젠가는 온다"를 기다리지 않고 상한에서 끊는 것이 이 예외의 존재 이유다.
 */
class GenerationTimeoutException(prompt: String, timeoutMillis: Long) :
    GenerationException("이미지 생성 타임아웃(${timeoutMillis}ms): prompt=$prompt")
