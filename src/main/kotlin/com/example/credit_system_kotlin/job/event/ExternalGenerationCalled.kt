package com.example.credit_system_kotlin.job.event

/**
 * 외부 생성 API 를 한 번 부르기 직전에 발행하는 순수 도메인 이벤트다.
 *
 * INV-04b(요구서 4-3, 2026-09-23)는 **불변식이 아니라 지표**다. 외부 API 에 멱등키가 없다고
 * 가정하므로 중복 호출을 막을 수단이 원리적으로 없고, 따라서 막는 대신 **센다.**
 *
 * 같은 job 의 두 번째 이상의 시도(`attemptNo > 0`)가 곧 그 job 에 대한 중복 외부 호출이다.
 * 재시도는 앞 호출이 실패했을 때만 나지만, 외부에서 실제로 무엇이 일어났는지는 알 수 없다 —
 * 타임아웃이나 늦은 워커의 호출은 과금됐을 수 있다. 그 최대치를 재는 것이 이 이벤트의 뜻이다.
 *
 * [jobId] 와 [attemptNo] 는 로그로 개별 건을 잇기 위한 것이지 지표 태그가 아니다.
 * [JobRecovered] 와 같은 이유로 태그에 쓰면 카디널리티가 폭발한다.
 */
data class ExternalGenerationCalled(
    val jobId: Long,
    val attemptNo: Int
)
