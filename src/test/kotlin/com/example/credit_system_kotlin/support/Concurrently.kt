package com.example.credit_system_kotlin.support

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [threadCount] 개의 스레드를 모두 준비시킨 뒤 한꺼번에 출발시켜 [action] 을 실행한다.
 * [action] 이 던지는 예외는 여기서 잡지 않는다. 호출하는 쪽 블록 안에서 잡는다.
 */
fun runConcurrently(threadCount: Int, action: (Int) -> Unit) {
    val executor = Executors.newFixedThreadPool(threadCount)
    val ready = CountDownLatch(threadCount)
    val start = CountDownLatch(1)
    val done = CountDownLatch(threadCount)

    repeat(threadCount) { idx ->
        executor.submit {
            ready.countDown()
            try {
                start.await()
                action(idx)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                done.countDown()
            }
        }
    }

    ready.await()
    start.countDown()
    done.await(30, TimeUnit.SECONDS)
    executor.shutdown()
}
