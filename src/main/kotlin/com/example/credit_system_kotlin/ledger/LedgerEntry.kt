package com.example.credit_system_kotlin.ledger

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
    organizationId: Long,
    jobId: Long?,
    type: LedgerType,
    amount: Long,
    idemKey: String?
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /**
     * persist 이후에만 유효한 id. 저장된 엔티티를 다루는 자리에서는 이쪽을 쓴다.
     * `id` 는 JPA가 persist 전 상태를 표현해야 해서 nullable로 남아 있을 뿐이다.
     */
    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 LedgerEntry입니다." }

    @Column(nullable = false)
    var organizationId: Long = organizationId
        protected set

    var jobId: Long? = jobId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var type: LedgerType = type
        protected set

    @Column(nullable = false)
    var amount: Long = amount
        protected set

    @Column(length = 100)
    var idemKey: String? = idemKey
        protected set

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
        protected set

    companion object {

        fun of(organizationId: Long, jobId: Long, type: LedgerType, amount: Long): LedgerEntry {
            require(type != LedgerType.CHARGE) {
                "CHARGE 타입은 멱등키 없이 생성할 수 없습니다. charge(organizationId, idemKey, amount)를 사용하세요."
            }
            return LedgerEntry(organizationId, jobId, type, amount, null)
        }

        fun charge(organizationId: Long, idemKey: String, amount: Long): LedgerEntry {
            require(idemKey.isNotBlank()) {
                "CHARGE 원장은 idemKey가 비어 있으면 안 됩니다: idemKey=$idemKey"
            }
            return LedgerEntry(organizationId, null, LedgerType.CHARGE, amount, idemKey)
        }
    }
}

enum class LedgerType {
    HOLD, CONFIRM, REFUND, CHARGE
}
