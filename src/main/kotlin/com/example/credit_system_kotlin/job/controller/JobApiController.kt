package com.example.credit_system_kotlin.job.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.dto.JobResponse
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/jobs")
class JobApiController(
    private val holdService: HoldService,
    private val jobQueryService: JobQueryService
) {

    @PostMapping
    fun create(
        currentUser: CurrentUser,
        @RequestBody request: JobCreateRequest
    ): HoldResult = holdService.requestGeneration(currentUser.userId, request.idemKey, request.prompt)

    @GetMapping
    fun list(currentUser: CurrentUser): List<JobResponse> =
        jobQueryService.findByUser(currentUser.userId)
}
