package de.openspri.rest

import de.openspri.domain.order.OrderActionRejected
import de.openspri.rest.order.ActionDto
import de.openspri.soap.NotFoundException
import de.openspri.soap.PayloadInvalidException
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

    @ExceptionHandler(PayloadInvalidException::class)
    fun invalidPayload(e: PayloadInvalidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Der Inhalt ist nicht gültig").apply {
            title = "Ungültiger Inhalt"
            setProperty("errors", e.errors)
        }

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: "Nicht gefunden")

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
            setProperty("allowedActions", e.allowed.map(ActionDto::of))
        }
}
