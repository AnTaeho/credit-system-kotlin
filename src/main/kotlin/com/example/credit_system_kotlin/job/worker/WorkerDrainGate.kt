package com.example.credit_system_kotlin.job.worker

import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 배포 드레인이 시작되면 닫히는 문. 한 번 닫히면 다시 열리지 않는다.
 *
 * **플래그가 아니라 락인 이유.** 단순한 `@Volatile var draining` 이면 "디스패처가 플래그를
 * 읽은 뒤, 아직 선점하기 전"이라는 창이 남는다. 그 창에서 드레인이 executor 를 내리면
 * 디스패처는 방금 선점한 job 을 넘길 곳이 없어 롤백하고 `WORKER_CLAIM/rolled_back` 을
 * 올린다. 그 경로는 안전망이지 정상 배포의 경로가 아니다. 문을 락으로 만들면
 * [close] 는 진행 중인 디스패치 주기가 끝나기를 기다렸다가 닫히므로, 반환 시점 이후에
 * 새 선점이 없다는 것이 상태가 아니라 구조로 보장된다.
 *
 * 워커가 꺼진 프로파일에서도 이 빈은 존재한다. 문만 있고 지나가는 사람이 없을 뿐이다.
 */
@Component
class WorkerDrainGate {

    private val lock = ReentrantLock()

    @Volatile
    private var open = true

    /** 문이 열려 있을 때만 [dispatchCycle] 을 실행한다. 실행 중에는 [close] 가 기다린다. */
    fun runIfOpen(dispatchCycle: () -> Unit) {
        lock.withLock {
            if (open) {
                dispatchCycle()
            }
        }
    }

    /** 진행 중인 디스패치 주기가 끝나기를 기다린 뒤 문을 닫는다. */
    fun close() {
        lock.withLock { open = false }
    }

    val isOpen: Boolean get() = open
}
