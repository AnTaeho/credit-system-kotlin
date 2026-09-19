package com.example.credit_system_kotlin.user.service

import com.example.credit_system_kotlin.global.exception.UserNotFoundException
import com.example.credit_system_kotlin.user.domain.User
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class UserFinder(
    private val userRepository: UserRepository
) {

    fun getOrThrow(userId: Long): User =
        userRepository.findByIdOrNull(userId)
            ?: throw UserNotFoundException(userId)
}
