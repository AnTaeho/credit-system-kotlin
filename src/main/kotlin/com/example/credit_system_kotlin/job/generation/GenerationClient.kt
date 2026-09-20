package com.example.credit_system_kotlin.job.generation

/**
 * 이미지 생성을 실제로 수행하는 외부 경계.
 *
 * 구현은 지금 스텁([com.example.credit_system_kotlin.job.generation.stub.GenerationStubClient])
 * 하나뿐이고, 다음 단계에서 진짜 Claude 구현이 같은 자리에 들어온다. 워커는 구체 구현이 아니라
 * 이 인터페이스에만 의존하므로 "외부가 스텁이냐 진짜냐"가 파이프라인 코드에 새지 않는다.
 *
 * **타임아웃은 이 경계의 구현이 소유한다.** 호출자는 별도 스레드로 감싸지 않는다. 스텁은
 * `app.generation.timeout-seconds` 를 스스로 지키고, 진짜 Claude 구현에서는 같은 값이 SDK
 * 클라이언트의 타임아웃 설정으로 넘어간다. 구현이 제 타임아웃을 지키지 못하는 경우(=hang)를
 * 위한 바깥쪽 절대 상한은 별도 조각에서 붙인다.
 *
 * 반환값은 결과 이미지 URL 하나다. 사용량·원가를 돌려줘야 하는 시점에 이 시그니처를 바꾼다.
 */
fun interface GenerationClient {

    /** @throws GenerationException 생성 실패 또는 타임아웃 */
    fun generate(prompt: String): String
}
