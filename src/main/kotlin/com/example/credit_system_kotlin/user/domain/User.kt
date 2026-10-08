package com.example.credit_system_kotlin.user.domain

import com.example.credit_system_kotlin.global.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_users_email", columnNames = ["email"])
    ]
)
class User(

    @Column(nullable = false)
    val name: String,

    balance: Long,

    email: String? = null,

    passwordHash: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: UserRole = UserRole.USER

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

    @Column
    var email: String? = email
        protected set

    @Column(length = 60)
    var passwordHash: String? = passwordHash
        protected set

    companion object {
        fun signUp(email: String, passwordHash: String): User =
            join(email, passwordHash, UserRole.USER)

        private fun join(email: String, passwordHash: String, role: UserRole): User =
            User(email.substringBefore('@'), 0L, email = email, passwordHash = passwordHash, role = role)
    }
}
