package com.example.credit_system_kotlin.user.domain

import com.example.credit_system_kotlin.global.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_email", columnNames = ["email"]),
        UniqueConstraint(name = "uk_users_google_sub", columnNames = ["googleSub"])
    ]
)
class User(

    @Column(nullable = false)
    val name: String,
    balance: Long

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    val persistedId: Long
        get() = requireNotNull(id) { "아직 저장되지 않은 User입니다." }

    @Column(nullable = false)
    var balance: Long = balance
        protected set

    @Column(nullable = false)
    var initialBalance: Long = balance
        protected set

    /** 구글 로그인으로 채운다. 로그인 전에 만들어진 행은 비어 있다. */
    @Column
    var email: String? = null
        protected set

    /** 구글 계정의 고유 식별자(sub). 이메일은 바뀔 수 있어서 이쪽을 신원으로 쓴다. */
    @Column
    var googleSub: String? = null
        protected set
}
