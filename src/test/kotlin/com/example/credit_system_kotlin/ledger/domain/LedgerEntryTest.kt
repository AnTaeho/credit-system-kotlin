package com.example.credit_system_kotlin.ledger.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LedgerEntryTest {

    @Test
    fun `hold는 amount를 음수로 기록한다`() {
        val entry = LedgerEntry.hold(1L, 10L, 100L)

        assertThat(entry.type).isEqualTo(LedgerType.HOLD)
        assertThat(entry.jobId).isEqualTo(10L)
        assertThat(entry.amount).isEqualTo(-100L)
    }

    @Test
    fun `confirm은 amount가 0이다`() {
        val entry = LedgerEntry.confirm(1L, 10L)

        assertThat(entry.type).isEqualTo(LedgerType.CONFIRM)
        assertThat(entry.jobId).isEqualTo(10L)
        assertThat(entry.amount).isEqualTo(0L)
    }

    @Test
    fun `hold의 cost가 양수가 아니면 예외가 발생한다`() {
        assertThatThrownBy { LedgerEntry.hold(1L, 10L, 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `charge는 jobId가 없다`() {
        val entry = LedgerEntry.charge(1L, 500L)

        assertThat(entry.type).isEqualTo(LedgerType.CHARGE)
        assertThat(entry.jobId).isNull()
        assertThat(entry.amount).isEqualTo(500L)
    }
}
