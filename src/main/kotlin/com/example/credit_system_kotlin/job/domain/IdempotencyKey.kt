package com.example.credit_system_kotlin.job.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = [UniqueConstraint(name = "uk_idempotency_org_key", columnNames = ["organizationId", "idemKey"])],
    indexes = [Index(name = "idx_idem_created_at", columnList = "createdAt")]
)
class IdempotencyKey(
    organizationId: Long,
    idemKey: String
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var organizationId: Long = organizationId
        protected set

    @Column(nullable = false, length = 100)
    var idemKey: String = idemKey
        protected set

    var jobId: Long? = null
        protected set

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
        protected set
}
