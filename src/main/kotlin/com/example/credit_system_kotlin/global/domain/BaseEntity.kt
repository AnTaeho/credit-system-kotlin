package com.example.credit_system_kotlin.global.domain

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Instant

@MappedSuperclass
abstract class BaseEntity protected constructor() {

    @Column(nullable = false)
    var createdAt: Instant = Instant.now()
        protected set

    @Column(nullable = false)
    var updatedAt: Instant = createdAt
        protected set
}
