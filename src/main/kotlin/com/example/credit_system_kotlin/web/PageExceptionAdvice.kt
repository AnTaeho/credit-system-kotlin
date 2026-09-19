package com.example.credit_system_kotlin.web

import com.example.credit_system_kotlin.global.exception.JobNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.ModelAndView

/**
 * 화면 컨트롤러의 예외를 JSON 이 아니라 화면으로 돌려준다.
 *
 * [com.example.credit_system_kotlin.global.exception.GlobalExceptionHandler] 는 범위 없는
 * `@RestControllerAdvice` 라 화면 컨트롤러에도 걸린다. 이 어드바이스를 이 패키지로 좁히고 먼저 돌게 해서
 * 화면에서 난 404 는 404 화면이 되게 한다. API 쪽 오류 본문(`ErrorResponse`)은 그대로다.
 */
@ControllerAdvice(basePackageClasses = [PageController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PageExceptionAdvice {

    @ExceptionHandler(JobNotFoundException::class)
    fun handleJobNotFound(): ModelAndView = ModelAndView(PageController.NOT_FOUND_VIEW, HttpStatus.NOT_FOUND)
}
