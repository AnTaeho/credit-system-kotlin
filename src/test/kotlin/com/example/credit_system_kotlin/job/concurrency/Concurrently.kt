package com.example.credit_system_kotlin.job.concurrency

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [threadCount] 개의 스레드를 모두 준비시킨 뒤 한꺼번에 출발시켜 [action] 을 실행한다.
 *
 * [timeoutSeconds] 는 전원이 끝나기를 기다리는 상한이다. 기본 30초는 기존 호출부(10 스레드)의
 * 값 그대로이고, 규모를 크게 올리는 호출부만 늘려 쓴다. **상한을 넘겨도 여기서 실패시키지
 * 않는다** — 넘겼는지는 호출부가 "성공 + 거절 = 스레드 수" 같은 합계 단언으로 잡아야 한다.
 *
 * 진짜 경쟁 상태를 만들려면 스레드를 띄우는 것만으로는 부족하고 출발선을 맞춰야 한다.
 * Java 원본은 테스트마다 이 3-latch 패턴을 직접 짰지만 세 테스트가 모두 같은 모양이라 하나로 묶었다.
 *
 * [action] 이 던지는 도메인 예외는 각 테스트가 기대하는 바가 서로 달라서
 * 여기서 잡지 않는다. 호출하는 쪽 블록 안에서 잡아라.
 */
fun runConcurrently(threadCount: Int, timeoutSeconds: Long = 30, action: (Int) -> Unit) {
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
    done.await(timeoutSeconds, TimeUnit.SECONDS)
    executor.shutdown()
}
