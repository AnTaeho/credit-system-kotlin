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
        UniqueConstraint(name = "uk_users_email", columnNames = ["email"]),
        UniqueConstraint(name = "uk_users_google_sub", columnNames = ["googleSub"])
    ]
)
class User(

    @Column(nullable = false)
    val name: String,
    balance: Long,
    email: String? = null,
    googleSub: String? = null,
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

    /** 구글 로그인으로 채운다. 로그인 전에 만들어진 행은 비어 있다. */
    @Column
    var email: String? = email
        protected set

    /** 구글 계정의 고유 식별자(sub). 이메일은 바뀔 수 있어서 이쪽을 신원으로 쓴다. */
    @Column
    var googleSub: String? = googleSub
        protected set

    /** BCrypt 해시. 비밀번호 로그인 전에 만들어진 행은 비어 있고, 그 행은 비밀번호로 로그인할 수 없다. */
    @Column(length = 60)
    var passwordHash: String? = passwordHash
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: UserRole = role
        protected set

    /** 구글 계정의 이메일이 바뀌었을 때 따라간다. 신원은 [googleSub] 이라 행은 그대로다. */
    fun changeEmail(newEmail: String) {
        email = newEmail
    }

    /**
     * 구글 계정을 처음 이 행에 묶는다. 개발 로그인으로 먼저 만들어진 행처럼 sub 가 비어 있는
     * 행에만 허용한다. 이미 다른 sub 가 묶인 행을 다른 계정이 가져가면 안 된다.
     */
    fun linkGoogleAccount(sub: String) {
        check(googleSub == null) { "이미 다른 구글 계정이 연결된 사용자입니다." }
        googleSub = sub
    }

    /** 이미 가입한 사용자를 운영자로 올리거나 내린다. */
    fun changeRole(newRole: UserRole) {
        role = newRole
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
