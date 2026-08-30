package com.example.credit_system_kotlin.job.domain

import com.example.credit_system_kotlin.global.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "jobs", indexes = [Index(name = "idx_jobs_status_id", columnList = "status, id")])
class Job private constructor(

    @Column(nullable = false)
    val organizationId: Long,

    @Column(nullable = false)
    val holdAmount: Long,

    @Column(nullable = false, length = 1000)
    val prompt: String

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 Job입니다." }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: JobStatus = JobStatus.HOLDING
        protected set

    var resultUrl: String? = null
        protected set

    fun startProcessing() {
        this.status = JobStatus.PROCESSING
        this.updatedAt = Instant.now()
    }

    fun complete(resultUrl: String) {
        this.status = JobStatus.COMPLETED
        this.resultUrl = resultUrl
        this.updatedAt = Instant.now()
    }

    fun fail() {
        this.status = JobStatus.FAILED
        this.updatedAt = Instant.now()
    }

    companion object {
        fun hold(organizationId: Long, holdAmount: Long, prompt: String): Job =
            Job(organizationId, holdAmount, prompt)
    }
}
