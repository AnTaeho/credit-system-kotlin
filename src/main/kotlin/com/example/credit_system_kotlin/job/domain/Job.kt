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
@Table(
    name = "jobs",
    indexes = [
        Index(name = "idx_jobs_status_id", columnList = "status, id"),
        Index(name = "idx_jobs_user_id", columnList = "userId, id")
    ]
)
class Job private constructor(

    @Column(nullable = false)
    val userId: Long,

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

    @Column(nullable = false)
    var attemptNo: Int = 0
        protected set

    var resultUrl: String? = null
        protected set

    /**
     * 다음 시도가 가능해지는 시각. `null` 이면 지금 바로 가능하다 — 최초 접수가 그렇다.
     * 재시도가 [com.example.credit_system_kotlin.global.config.AppProperties.RetryBackoff] 만큼
     * 미래로 채운다.
     *
     * 종결(COMPLETED/REFUNDED)이나 선점(PROCESSING) 때 지우지 않는다. 이 값을 보는 곳은
     * HOLDING 을 집는 디스패처뿐이고, 다음 재시도가 어차피 덮어쓰기 때문에 남은 값은 무해하다.
     */
    var nextAttemptAt: Instant? = null
        protected set

    companion object {
        fun hold(userId: Long, holdAmount: Long, prompt: String): Job =
            Job(userId, holdAmount, prompt)
    }
}
