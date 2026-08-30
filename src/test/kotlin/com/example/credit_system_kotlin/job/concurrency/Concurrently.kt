package com.example.credit_system_kotlin.job.concurrency

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [threadCount] 개의 스레드를 모두 준비시킨 뒤 한꺼번에 출발시켜 [action] 을 실행한다.
 *
 * 진짜 경쟁 상태를 만들려면 스레드를 띄우는 것만으로는 부족하고 출발선을 맞춰야 한다.
 * Java 원본은 테스트마다 이 3-latch 패턴을 직접 짰지만 세 테스트가 모두 같은 모양이라 하나로 묶었다.
 *
 * [action] 이 던지는 도메인 예외는 각 테스트가 기대하는 바가 서로 달라서
 * 여기서 잡지 않는다. 호출하는 쪽 블록 안에서 잡아라.
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
