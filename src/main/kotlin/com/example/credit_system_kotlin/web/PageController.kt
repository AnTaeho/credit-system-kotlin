package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.auth.CurrentUser
import com.example.credit_system_kotlin.global.paging.CursorRequest
import com.example.credit_system_kotlin.job.service.JobQueryService
import com.example.credit_system_kotlin.ledger.service.LedgerQueryService
import com.example.credit_system_kotlin.user.service.UserFinder
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView

/**
 * 로그인한 사람의 화면. 서버는 첫 화면만 그리고 그 뒤 변화는 `static/js` 가 `/api` 를 불러 처리한다.
 * 화면 전용 API 는 없다.
 */
@Controller
class PageController(
    private val userFinder: UserFinder,
    private val jobQueryService: JobQueryService,
    private val ledgerQueryService: LedgerQueryService
) {

    /** 생성 요청 목록은 첫 페이지만 그린다. */
    @GetMapping("/")
    fun home(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        model.addAttribute("jobs", jobQueryService.findByUser(currentUser.userId, CursorRequest.of(null, null)))
        return "home"
    }

    /** 남의 job·없는 job·숫자가 아닌 id 는 모두 같은 404 화면이다. 존재 여부를 흘리지 않는다. */
    @GetMapping("/jobs/{id}")
    fun job(currentUser: CurrentUser, @PathVariable id: String, model: Model): Any {
        val jobId = id.toLongOrNull() ?: return notFound()
        addLayout(currentUser, model)
        model.addAttribute("job", jobQueryService.findOne(currentUser.userId, jobId))
        return "job"
    }

    /** 원장도 첫 페이지만 그린다. */
    @GetMapping("/ledger")
    fun ledger(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        model.addAttribute("entries", ledgerQueryService.findByUser(currentUser.userId, CursorRequest.of(null, null)))
        return "ledger"
    }

    /** 운영자 지급 화면. ROLE_ADMIN 검사는 SecurityConfig 가 한다. 운영자 자신의 userId 를 보여 줘 자기에게도 지급할 수 있게 한다. */
    @GetMapping("/admin")
    fun admin(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        return "admin"
    }

    /** 모든 화면이 쓰는 머리 값. [CurrentUser] 는 id 와 역할만 알아서 이메일과 잔액은 사용자 행에서 읽는다. */
    private fun addLayout(currentUser: CurrentUser, model: Model) {
        val user = userFinder.getOrThrow(currentUser.userId)
        model.addAttribute("userId", currentUser.userId)
        model.addAttribute("admin", currentUser.admin)
        model.addAttribute("email", user.email ?: user.name)
        model.addAttribute("balance", user.balance)
    }

    private fun notFound(): ModelAndView = ModelAndView(NOT_FOUND_VIEW, HttpStatus.NOT_FOUND)

    companion object {
        const val NOT_FOUND_VIEW = "error/404"
    }
}
