package com.example.credit_system_kotlin.organization.domain

import com.example.credit_system_kotlin.global.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "organizations")
class Organization(
    name: String,
    balance: Long
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
        get() = requireNotNull(id) { "아직 저장되지 않은 Organization입니다." }

    @Column(nullable = false)
    var name: String = name
        protected set

    @Column(nullable = false)
    var balance: Long = balance
        protected set

    @Column(nullable = false)
    var initialBalance: Long = balance
        protected set
}
