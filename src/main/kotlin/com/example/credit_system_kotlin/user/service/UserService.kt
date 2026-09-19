package com.example.credit_system_kotlin.user.service

import com.example.credit_system_kotlin.global.exception.InvalidRequestException
import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.global.validation.validateIdemKey
import com.example.credit_system_kotlin.ledger.domain.LedgerEntry
import com.example.credit_system_kotlin.ledger.repository.LedgerRepository
import com.example.credit_system_kotlin.user.dto.BalanceResponse
import com.example.credit_system_kotlin.user.dto.ChargeResponse
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = LoggerFactory.getLogger(UserService::class.java)

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userFinder: UserFinder,
    private val ledgerRepository: LedgerRepository
) {

    @Transactional(readOnly = true)
    fun getBalance(userId: Long): BalanceResponse {
        val user = userFinder.getOrThrow(userId)
        return BalanceResponse(user.balance)
    }

    @Transactional
    fun charge(userId: Long, idemKey: String, amount: Long): ChargeResponse {
        validateRequest(idemKey, amount)

        val existing = ledgerRepository.findByUserIdAndIdemKey(userId, idemKey)
        if (existing != null) {
            val balance = userFinder.getOrThrow(userId).balance
            log.info("중복 충전 요청 감지: userId={}, idemKey={}", userId, idemKey)
            return ChargeResponse(balance, true)
        }

        val updated = userRepository.addBalance(userId, amount, Instant.now())
        if (updated != 1) {
            throw UserNotFoundException(userId)
        }

        ledgerRepository.save(LedgerEntry.charge(userId, idemKey, amount))
        log.info("충전 완료: userId={}, amount={}", userId, amount)

        val balance = userFinder.getOrThrow(userId).balance
        return ChargeResponse(balance, false)
    }

    private fun validateRequest(idemKey: String, amount: Long) {
        validateIdemKey(idemKey)
        if (amount <= 0) {
            throw InvalidRequestException("amount는 0보다 커야 합니다.")
        }
        if (amount > MAX_CHARGE_AMOUNT) {
            throw InvalidRequestException("amount는 1,000,000을 초과할 수 없습니다.")
        }
    }

    companion object {
        private const val MAX_CHARGE_AMOUNT = 1_000_000L
    }
}
