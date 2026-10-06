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

/** 생성 요청 한 건. 상태·시도 번호·결과 URL 은 리포지토리의 조건부 UPDATE 로만 바뀐다. */
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

    companion object {
        /** HOLDING, 시도 번호 0 으로 만든다. 잔액 차감이 끝난 뒤에 부른다. */
        fun hold(userId: Long, holdAmount: Long, prompt: String): Job =
            Job(userId, holdAmount, prompt)
    }
}
