package com.example.credit_system_kotlin.user.service

import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** 없는 사용자는 [UserNotFoundException] 으로 던져 API 가 404 로 답하게 한다. */
@Component
class UserFinder(
    private val userRepository: UserRepository
) {

    fun getOrThrow(userId: Long): User =
        userRepository.findByIdOrNull(userId)
            ?: throw UserNotFoundException(userId)
}
