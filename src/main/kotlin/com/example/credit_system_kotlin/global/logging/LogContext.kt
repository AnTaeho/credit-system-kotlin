package com.example.credit_system_kotlin.global.logging

import org.slf4j.MDC

/**
 * 로그를 묶는 식별자의 이름 한 곳.
 *
 * **식별자를 하나로 합치지 않고 둘로 나눈 이유.**
 * job 하나는 HTTP 접수 → 워커 → 외부 호출 → 회수 스케줄러를 거치는데, 이 구간들은 수명도 스레드도 다르다.
 * - [REQUEST_ID] 는 HTTP 요청 한 번의 수명이다. 워커 스레드에는 애초에 존재할 수 없다
 *   (접수 응답은 이미 나갔고, 재시도는 며칠 뒤에 돌 수도 있다).
 * - [JOB_ID] · [ATTEMPT_NO] 는 HTTP 밖에서 도는 경로가 스스로 심는다.
 *
 * **두 세계를 잇는 곳은 접수 로그 한 줄이다.** hold 가 성공해 jobId 가 생기는 지점
 * (`HoldService.requestGeneration` 의 "hold 완료" 줄)은 아직 HTTP 스레드 위라 requestId 가 MDC 에 있고,
 * 그 줄이 jobId 를 함께 찍는다. 그래서 requestId → jobId 의 연결이 그 한 줄에 남고, 그 뒤는 jobId 로 따라간다.
 *
 * 로드맵은 "같은 ID 로 묶인다"고 적었지만 그렇게 하지 않았다. 요청 ID 를 워커까지 끌고 가려면 jobs 행에
 * 저장해야 하고, 그건 마이그레이션 한 장과 컬럼 하나를 영구히 지는 일이다. 사용자 1명 규모에서 얻는 것은
 * "접수 줄을 한 번 더 안 봐도 된다" 뿐이라 값어치가 비용보다 작다고 봤다. 규모가 커져 접수 줄을 찾는 일이
 * 실제 비용이 되면 그때 컬럼을 추가하면 된다 — 그때도 이 파일의 키 이름은 그대로 쓸 수 있다.
 */
object LogContext {

    /** HTTP 요청 한 번의 식별자. [RequestIdFilter] 가 심고 지운다. */
    const val REQUEST_ID = "requestId"

    /** job 행의 id. HTTP 밖 경로가 심는다. */
    const val JOB_ID = "jobId"

    /** 같은 job 의 몇 번째 시도인지. jobId 만으로는 재시도가 겹쳐 보인다. */
    const val ATTEMPT_NO = "attemptNo"
}

/**
 * job 단위 MDC 를 걸고 [block] 을 돌린 뒤 **반드시** 원래대로 되돌린다.
 *
 * `finally` 가 이 함수의 존재 이유다. 워커와 스케줄러는 스레드 풀 위에서 돈다. job A 를 처리한 스레드가
 * MDC 를 지우지 않은 채 반납되면, 그 스레드가 다음에 집는 job B 의 로그에 A 의 jobId 가 따라붙는다.
 * 그러면 식별자가 추적을 돕는 게 아니라 거짓말을 하게 된다.
 *
 * 지우는 대신 **이전 값을 복원**한다. 지금은 중첩이 없어 결과가 같지만, 나중에 이 블록 안에서 다른 job 의
 * 블록이 열려도 바깥 값이 살아 돌아온다. 이전 값이 없었으면 [MDC.remove] 와 같은 효과다.
 */
fun <T> withJobLogContext(jobId: Long, attemptNo: Int, block: () -> T): T {
    val previousJobId = MDC.get(LogContext.JOB_ID)
    val previousAttemptNo = MDC.get(LogContext.ATTEMPT_NO)
    MDC.put(LogContext.JOB_ID, jobId.toString())
    MDC.put(LogContext.ATTEMPT_NO, attemptNo.toString())
    try {
        return block()
    } finally {
        restore(LogContext.JOB_ID, previousJobId)
        restore(LogContext.ATTEMPT_NO, previousAttemptNo)
    }
}

private fun restore(key: String, previous: String?) {
    if (previous == null) {
        MDC.remove(key)
    } else {
        MDC.put(key, previous)
    }
}
