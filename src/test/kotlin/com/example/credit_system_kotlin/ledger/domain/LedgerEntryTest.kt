package com.example.credit_system_kotlin.ledger.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LedgerEntryTest {

    @Test
    fun `of에 CHARGE 타입을 넣으면 예외가 발생한다`() {
        assertThatThrownBy { LedgerEntry.of(1L, 10L, LedgerType.CHARGE, 500L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("charge(")
    }

    @ParameterizedTest
    @EnumSource(value = LedgerType::class, names = ["HOLD", "CONFIRM", "REFUND"])
    fun `of에 CHARGE가 아닌 타입은 정상 생성되고 idemKey가 없다`(type: LedgerType) {
        val entry = LedgerEntry.of(1L, 10L, type, -100L)

        assertThat(entry.type).isEqualTo(type)
        assertThat(entry.jobId).isEqualTo(10L)
        assertThat(entry.idemKey).isNull()
    }

    @Test
    fun `charge는 jobId가 없고 idemKey가 채워진다`() {
        val entry = LedgerEntry.charge(1L, "idem-key-1", 500L)

        assertThat(entry.type).isEqualTo(LedgerType.CHARGE)
        assertThat(entry.jobId).isNull()
        assertThat(entry.idemKey).isEqualTo("idem-key-1")
    }

    @Test
    fun `charge에 idemKey가 공백이면 예외가 발생한다`() {
        assertThatThrownBy { LedgerEntry.charge(1L, "   ", 500L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
