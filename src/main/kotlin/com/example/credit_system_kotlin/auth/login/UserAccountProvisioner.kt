package com.example.credit_system_kotlin.auth.login

import com.example.credit_system_kotlin.auth.config.maskEmail
import com.example.credit_system_kotlin.auth.config.normalizeEmail
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

private val log = LoggerFactory.getLogger(UserAccountProvisioner::class.java)

/**
 * 로그인한 사람에게 대응하는 사용자 행을 찾거나 만든다. 허용 목록 검사는 호출하는 쪽이 이미 끝냈다.
 *
 * **동시 첫 로그인:** 같은 사람이 탭 두 개로 동시에 처음 로그인하면 둘 다 "없음"을 보고 둘 다 INSERT 한다.
 * 한쪽은 유니크 제약(`uk_users_google_sub`, 개발 로그인은 `uk_users_email`)에 걸린다. 진 쪽은
 * 자기 트랜잭션을 롤백하고 **새 트랜잭션에서 다시 조회**해 이긴 쪽의 행으로 수렴한다.
 * 조회와 재조회를 같은 트랜잭션에 두면 롤백 전용이 된 트랜잭션에서 읽게 되므로 반드시 둘로 나눈다.
 */
@Component
class UserAccountProvisioner(
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate
) {

    /** 구글 로그인. 신원은 [googleSub] 이다. 이메일은 바뀔 수 있어 따라가기만 한다. */
    fun provisionGoogleUser(googleSub: String, email: String, name: String): Long {
        val normalized = normalizeEmail(email)
        return convergeOnConflict(
            attempt = { findOrCreateGoogleUser(googleSub, normalized, name) },
            reread = { userRepository.findByGoogleSub(googleSub) }
        )
    }

    /** 개발 로그인. 구글 sub 가 없으므로 이메일로 찾는다. */
    fun provisionDevUser(email: String): Long {
        val normalized = normalizeEmail(email)
        return convergeOnConflict(
            attempt = {
                userRepository.findByEmail(normalized)
                    ?: userRepository.saveAndFlush(User(normalized.substringBefore('@'), 0L, email = normalized))
            },
            reread = { userRepository.findByEmail(normalized) }
        )
    }

    private fun findOrCreateGoogleUser(googleSub: String, email: String, name: String): User {
        val bySub = userRepository.findByGoogleSub(googleSub)
        if (bySub != null) {
            if (bySub.email != email) {
                log.info("구글 계정 이메일 변경 반영: userId={}, email={}", bySub.persistedId, maskEmail(email))
                bySub.changeEmail(email)
                userRepository.flush()
            }
            return bySub
        }

        // 개발 로그인 등으로 이메일만 가진 행이 먼저 있으면 그 행에 구글 계정을 묶는다.
        // 허용 목록과 email_verified 를 통과한 이메일이므로 같은 사람으로 본다.
        val byEmail = userRepository.findByEmail(email)
        if (byEmail != null) {
            // 같은 이메일에 이미 다른 구글 계정이 묶여 있으면 이 로그인이 그 행을 가져가면 안 된다.
            // User.linkGoogleAccount 의 check() 는 불변식 방어로 두고, 여기서 먼저 판단해 거부로 돌린다.
            if (byEmail.googleSub != null) {
                throw GoogleAccountConflictException()
            }
            byEmail.linkGoogleAccount(googleSub)
            userRepository.flush()
            log.info("기존 사용자에 구글 계정 연결: userId={}", byEmail.persistedId)
            return byEmail
        }

        val created = userRepository.saveAndFlush(User(name, 0L, email = email, googleSub = googleSub))
        log.info("첫 로그인 사용자 생성: userId={}, email={}", created.persistedId, maskEmail(email))
        return created
    }

    private fun convergeOnConflict(attempt: () -> User, reread: () -> User?): Long =
        try {
            requireNotNull(transactionTemplate.execute { attempt().persistedId })
        } catch (e: DataIntegrityViolationException) {
            // 원인 메시지에는 중복된 이메일이 그대로 들어 있어 남기지 않는다.
            log.info("동시 첫 로그인 경합 — 먼저 만들어진 행으로 수렴한다")
            transactionTemplate.execute { reread()?.persistedId }
                ?: throw IllegalStateException("유니크 제약 위반 뒤 재조회에서도 사용자를 찾지 못했습니다.", e)
        }
}

/** 이메일이 같은 행에 이미 다른 구글 계정(sub)이 연결돼 있다. 로그인 거부 사유다. */
class GoogleAccountConflictException : RuntimeException("이미 다른 구글 계정이 연결된 이메일입니다.")
