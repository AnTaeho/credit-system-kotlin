package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.global.exception.JobNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView

/**
 * `GlobalExceptionHandler` 가 범위 없는 `@RestControllerAdvice` 라 화면 컨트롤러에도 걸린다.
 * 이 패키지로 좁힌 어드바이스를 먼저 돌려 화면에서 난 404 는 404 화면으로 답한다.
 */
@ControllerAdvice(basePackageClasses = [PageController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PageExceptionAdvice {

    @ExceptionHandler(JobNotFoundException::class)
    fun handleJobNotFound(): ModelAndView = ModelAndView(PageController.NOT_FOUND_VIEW, HttpStatus.NOT_FOUND)
}
