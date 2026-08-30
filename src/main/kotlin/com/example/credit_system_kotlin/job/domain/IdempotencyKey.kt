package com.example.credit_system_kotlin.job.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_idempotency_org_key", columnNames = ["organizationId", "idemKey"])
    ]
)
class IdempotencyKey(

    @Column(nullable = false)
    val organizationId: Long,

    @Column(nullable = false, length = 100)
    val idemKey: String

) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 IdempotencyKey입니다." }

    var jobId: Long? = null
        protected set

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
        protected set
}
