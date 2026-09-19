package com.example.credit_system_kotlin.job.controller

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.global.paging.CursorPage
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.job.dto.HoldResult
import com.example.credit_system_kotlin.job.dto.JobCreateRequest
import com.example.credit_system_kotlin.job.dto.JobResponse
import com.example.credit_system_kotlin.job.service.HoldService
import com.example.credit_system_kotlin.job.service.JobQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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

    /** 페이지 파라미터는 문자열로 받는다. 해석과 검증은 [CursorRequest] 가 한다. */
    @GetMapping
    fun list(
        currentUser: CurrentUser,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: String?
    ): CursorPage<JobResponse> =
        jobQueryService.findByUser(currentUser.userId, CursorRequest.of(cursor, size))

    @GetMapping("/{id}")
    fun get(currentUser: CurrentUser, @PathVariable id: Long): JobResponse =
        jobQueryService.findOne(currentUser.userId, id)
}
