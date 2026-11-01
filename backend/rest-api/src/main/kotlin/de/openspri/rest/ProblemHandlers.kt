package de.openspri.rest

import de.openspri.domain.order.OrderActionRejected
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Error responses as RFC 9457 Problem Details. Business rejections carry the list of reasons with their
 * rule references (e.g. "R-05: …") in the property `reasons`.
 */
@RestControllerAdvice
class ProblemHandlers {

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "Ungültige Anfrage")

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: "Nicht gefunden")

    @ExceptionHandler(IllegalStateException::class)
    fun conflict(e: IllegalStateException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "Konflikt")

    @ExceptionHandler(OrderActionRejected::class)
    fun rejected(e: OrderActionRejected): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Die Aktion ist nicht zulässig").apply {
            title = "Aktion nicht zulässig"
            setProperty("reasons", e.reasons)
        }
}
