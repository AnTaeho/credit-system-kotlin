package com.example.credit_system_kotlin.job.api

import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
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
        @RequestHeader("X-Organization-Id") organizationId: Long,
        @RequestBody request: JobCreateRequest
    ): HoldResult = holdService.requestGeneration(organizationId, request.idemKey, request.prompt)

    @GetMapping
    fun list(@RequestHeader("X-Organization-Id") organizationId: Long): List<JobResponse> =
        jobQueryService.findByOrganization(organizationId)
}
