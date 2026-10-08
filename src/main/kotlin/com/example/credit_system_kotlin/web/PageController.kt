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

@Controller
class PageController(
    private val userFinder: UserFinder,
    private val jobQueryService: JobQueryService,
    private val ledgerQueryService: LedgerQueryService
) {

    @GetMapping("/")
    fun home(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        model.addAttribute("jobs", jobQueryService.findByUser(currentUser.userId, CursorRequest.of(null, null)))
        return "home"
    }

    @GetMapping("/jobs/{id}")
    fun job(currentUser: CurrentUser, @PathVariable id: String, model: Model): Any {
        val jobId = id.toLongOrNull() ?: return notFound()
        addLayout(currentUser, model)
        model.addAttribute("job", jobQueryService.findOne(currentUser.userId, jobId))
        return "job"
    }

    @GetMapping("/ledger")
    fun ledger(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        model.addAttribute("entries", ledgerQueryService.findByUser(currentUser.userId, CursorRequest.of(null, null)))
        return "ledger"
    }

    @GetMapping("/admin")
    fun admin(currentUser: CurrentUser, model: Model): String {
        addLayout(currentUser, model)
        return "admin"
    }

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
