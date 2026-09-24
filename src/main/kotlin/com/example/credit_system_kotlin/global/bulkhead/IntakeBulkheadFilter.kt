package com.example.credit_system_kotlin.global.bulkhead

import com.example.credit_system_kotlin.observability.IntakeBulkheadMetrics
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.Semaphore

/**
 * 접수 경로(`/api` 아래)가 동시에 쓸 수 있는 수를 세마포어로 묶는다.
 *
 * **왜 있나.** DB 커넥션 풀은 웹과 배경 작업이 같이 쓴다. 부하가 걸리면 접수 요청이 풀을
 * 전부 점유해서 스냅샷·회수·대사·워커 디스패처가 커넥션을 못 잡는다(`docs/SYSTEM.md` 5절).
 * 풀을 진짜로 둘로 가르려면 DataSource·EntityManagerFactory·TransactionManager 를 복제하고
 * 웹과 스케줄러가 공유하는 리포지토리까지 갈라야 한다. 대신 접수 쪽 동시 사용을 풀 크기보다
 * **낮게** 묶으면 남는 몫이 항상 배경 작업의 것이 된다.
 *
 * **거절하지 않는다.** [Semaphore.acquire] 는 블로킹이고 타임아웃도 없다. 상한을 넘은 요청은
 * 거절되는 것이 아니라 기다린다. 거절(백프레셔)은 서비스가 클라이언트에게 하는 약속을 바꾸는
 * 별개의 변경이라 여기에 섞지 않는다.
 *
 * **공정 모드.** 불공정 세마포어는 방금 놓은 스레드가 곧바로 다시 집어가는 것을 허용해서
 * 일부 요청이 굶는다. 꼬리 지연으로 나타나므로 공정 모드로 둔다.
 *
 * **허가 획득은 try 밖이다.** [Semaphore.acquire] 가 인터럽트로 깨지면 허가를 받지 못한
 * 것이므로 `finally` 의 [Semaphore.release] 가 돌면 없던 허가가 하나 생긴다.
 */
class IntakeBulkheadFilter(
    private val permits: Semaphore,
    private val metrics: IntakeBulkheadMetrics
) : OncePerRequestFilter() {

    /** 접수만 묶는다. 액추에이터·정적 자원·로그인 화면은 커넥션을 거의 쓰지 않고, 막히면 안 된다. */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith(INTAKE_PATH_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        acquirePermit()
        try {
            filterChain.doFilter(request, response)
        } finally {
            permits.release()
        }
    }

    private fun acquirePermit() {
        val startedAtNanos = System.nanoTime()
        try {
            permits.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ServletException("접수 허가를 기다리는 중에 인터럽트되었다", interrupted)
        } finally {
            // 기다리지 않고 바로 받은 경우도 기록한다. 그래야 count 와 sum 이
            // "몇 건 중 얼마나 기다렸나"에 답한다.
            metrics.recordWait(System.nanoTime() - startedAtNanos)
        }
    }

    companion object {
        const val INTAKE_PATH_PREFIX = "/api/"
        const val INTAKE_URL_PATTERN = "/api/*"
    }
}
