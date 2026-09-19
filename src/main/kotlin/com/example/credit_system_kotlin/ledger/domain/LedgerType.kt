package com.example.credit_system_kotlin.ledger.domain

enum class LedgerType {
    HOLD, CONFIRM, REFUND, CHARGE,

    /**
     * 운영자 지급. 결제 없이 운영자가 잔액을 올린 기록이라 CHARGE(결제 충전)와 구분한다.
     * 잔액을 늘리는 대변이므로 CHARGE 와 같이 양수로 기록된다.
     */
    ADMIN_GRANT
}
