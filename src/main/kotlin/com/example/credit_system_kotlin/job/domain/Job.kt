package com.example.credit_system_kotlin.job.domain

import com.example.credit_system_kotlin.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(name = "jobs", indexes = [Index(name = "idx_jobs_status_id", columnList = "status, id")])
class Job private constructor(
    organizationId: Long,
    holdAmount: Long,
    prompt: String
) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /**
     * persist 이후에만 유효한 id. 저장된 엔티티를 다루는 자리에서는 이쪽을 쓴다.
     * `id` 는 JPA가 persist 전 상태를 표현해야 해서 nullable로 남아 있을 뿐이다.
     */
    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 Job입니다." }

    @Column(nullable = false)
    var organizationId: Long = organizationId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: JobStatus = JobStatus.HOLDING
        protected set

    @Column(nullable = false)
    var attemptNo: Int = 0
        protected set

    @Column(nullable = false)
    var holdAmount: Long = holdAmount
        protected set

    @Column(nullable = false, length = 1000)
    var prompt: String = prompt
        protected set

    var resultUrl: String? = null
        protected set

    companion object {
        fun hold(organizationId: Long, holdAmount: Long, prompt: String): Job =
            Job(organizationId, holdAmount, prompt)
    }
}

enum class JobStatus {
    HOLDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    REFUNDED
}
