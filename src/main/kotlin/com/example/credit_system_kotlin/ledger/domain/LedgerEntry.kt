package com.example.credit_system_kotlin.ledger.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "ledger_entries",
    indexes = [Index(name = "idx_ledger_org_id", columnList = "organizationId")],
    uniqueConstraints = [UniqueConstraint(name = "uk_ledger_org_idem", columnNames = ["organizationId", "idemKey"])]
)
class LedgerEntry private constructor(

    @Column(nullable = false)
    val organizationId: Long,

    val jobId: Long?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val type: LedgerType,

    @Column(nullable = false)
    val amount: Long,

    @Column(length = 100)
    val idemKey: String?

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 LedgerEntry입니다." }

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
        protected set

    companion object {

        /** hold 는 잔액을 묶는 차변이라 음수로 기록된다. */
        fun hold(organizationId: Long, jobId: Long, cost: Long): LedgerEntry {
            require(cost > 0) { "hold 원장의 cost는 양수여야 합니다: cost=$cost" }
            return LedgerEntry(organizationId, jobId, LedgerType.HOLD, -cost, null)
        }

        /** confirm 은 hold 를 확정할 뿐 잔액을 움직이지 않아 금액이 0이다. */
        fun confirm(organizationId: Long, jobId: Long): LedgerEntry =
            LedgerEntry(organizationId, jobId, LedgerType.CONFIRM, 0, null)

        fun charge(organizationId: Long, idemKey: String, amount: Long): LedgerEntry {
            require(idemKey.isNotBlank()) {
                "CHARGE 원장은 idemKey가 비어 있으면 안 됩니다: idemKey=$idemKey"
            }
            return LedgerEntry(organizationId, null, LedgerType.CHARGE, amount, idemKey)
        }
    }
}
