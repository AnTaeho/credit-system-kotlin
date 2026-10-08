package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.global.exception.JobNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView

@ControllerAdvice(basePackageClasses = [PageController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PageExceptionAdvice {

    @ExceptionHandler(JobNotFoundException::class)
    fun handleJobNotFound(): ModelAndView = ModelAndView(PageController.NOT_FOUND_VIEW, HttpStatus.NOT_FOUND)
}
