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
    role: UserRole = UserRole.USER

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

    /** 로그인 이름. 소문자로 맞춘 값만 들어온다. 로그인이 생기기 전에 만들어진 행은 비어 있다. */
    @Column
    var email: String? = email
        protected set

    /** BCrypt 해시. 비밀번호 로그인 전에 만들어진 행은 비어 있고, 그 행은 비밀번호로 로그인할 수 없다. */
    @Column(length = 60)
    var passwordHash: String? = passwordHash
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = role
        protected set

    /** 이미 가입한 사용자를 운영자로 올리거나 내린다. */
    fun changeRole(newRole: UserRole) {
        role = newRole
    }

    /** 비밀번호를 새 해시로 바꾼다. 이미 나간 리프레시 토큰을 폐기하는 것은 부르는 쪽 몫이다. */
    fun changePassword(newPasswordHash: String) {
        passwordHash = newPasswordHash
    }

    companion object {
        /** 누구나 하는 가입. 이름은 이메일 @ 앞부분, 잔액 0, 역할 USER 로 시작한다. */
        fun signUp(email: String, passwordHash: String): User =
            withPassword(email, passwordHash, UserRole.USER)

        /** 운영자 계정. 가입 화면으로는 만들 수 없고 운영 경로(초기 계정 준비 등)에서만 쓴다. */
        fun admin(email: String, passwordHash: String): User =
            withPassword(email, passwordHash, UserRole.ADMIN)

        private fun withPassword(email: String, passwordHash: String, role: UserRole): User =
            User(email.substringBefore('@'), 0L, email = email, passwordHash = passwordHash, role = role)
    }
}
