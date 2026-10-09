package com.example.credit_system_kotlin.unit.global.config

import com.example.credit_system_kotlin.global.config.ThreadPoolConfig
import com.example.credit_system_kotlin.global.config.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThreadPoolConfigTest {

    @Test
    fun `heartbeat 스레드 풀은 워커 동시 실행 수만큼 만들어진다`() {
        val scheduler = ThreadPoolConfig().heartbeatScheduler(WorkerProperties(true, 20, 3))
        scheduler.initialize()

        try {
            assertThat(scheduler.scheduledThreadPoolExecutor.corePoolSize).isEqualTo(3)
            assertThat(scheduler.threadNamePrefix).isEqualTo("heartbeat-")
        } finally {
            scheduler.shutdown()
        }
    }
}
