package com.example.credit_system_kotlin.user.service

import com.example.credit_system_kotlin.global.config.AppProperties
import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.global.validation.validateIdemKey
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.dto.BalanceResponse
import com.example.credit_system_kotlin.user.dto.GrantResponse
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Locale

private val log = LoggerFactory.getLogger(UserService::class.java)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userFinder: UserFinder,
    private val ledgerRepository: LedgerRepository,
    private val appProperties: AppProperties
) {

    @Transactional(readOnly = true)
    fun getBalance(userId: Long): BalanceResponse {
        val user = userFinder.getOrThrow(userId)
        return BalanceResponse(user.balance)
    }

    /**
     * 운영자가 [userId] 에게 크레딧을 지급한다. 결제 없이 잔액을 올리는 유일한 경로다.
     *
     * 멱등성은 옛 충전과 같은 방식이다. 1차는 `(userId, idemKey)` 조회, 2차는 원장의 유니크 제약
     * `uk_ledger_user_idem` 이다. 같은 idemKey 재시도는 잔액을 다시 올리지 않고 duplicate 로 답한다.
     */
    @Transactional
    fun grant(adminUserId: Long, userId: Long, idemKey: String, amount: Long): GrantResponse {
        validateRequest(idemKey, amount)

        val existing = ledgerRepository.findByUserIdAndIdemKey(userId, idemKey)
        if (existing != null) {
            val balance = userFinder.getOrThrow(userId).balance
            log.info("중복 지급 요청 감지: adminUserId={}, userId={}, idemKey={}", adminUserId, userId, idemKey)
            return GrantResponse(balance, true)
        }

        val updated = userRepository.addBalance(userId, amount, Instant.now())
        if (updated != 1) {
            throw UserNotFoundException(userId)
        }

        ledgerRepository.save(LedgerEntry.adminGrant(userId, idemKey, amount))
        log.info("운영자 지급 완료: adminUserId={}, userId={}, amount={}", adminUserId, userId, amount)

        val balance = userFinder.getOrThrow(userId).balance
        return GrantResponse(balance, false)
    }

    private fun validateRequest(idemKey: String, amount: Long) {
        validateIdemKey(idemKey)
        if (amount <= 0) {
            throw InvalidRequestException("amount는 0보다 커야 합니다.")
        }
        val maxGrantAmount = appProperties.admin.maxGrantAmount
        if (amount > maxGrantAmount) {
            throw InvalidRequestException("amount는 %,d을 초과할 수 없습니다.".format(Locale.ROOT, maxGrantAmount))
        }
    }
}
