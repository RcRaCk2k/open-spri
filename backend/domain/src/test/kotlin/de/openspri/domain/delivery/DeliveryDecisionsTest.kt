package de.openspri.domain.delivery

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

/** Specification 11.2.3.1 (orders) and 11.2.3.2 (messages). */
class DeliveryDecisionsTest {

    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val policy = RetryPolicy(interval = Duration.ofMinutes(5), maxAttempts = 3, overloadedInterval = Duration.ofMinutes(1))

    private fun entry(type: RequestType, attempts: Int = 0) = OutboxEntry(
        1, PartnerId(1), null, type, if (type == RequestType.ORDER) SoapOperation.ANNEHMEN_AUFTRAG else SoapOperation.ANNEHMEN_MELDUNG,
        "A-1", "1:ORDER:A-1", ByteArray(0), attempts = attempts, nextAttemptAt = now, createdAt = now,
    )

    private fun teq(code: TeqCode) = AttemptResult.Acknowledged(code, code.code, code.text)

    @Test
    fun `positive TEQ delivers`() {
        assertEquals(DeliveryStatus.DELIVERED, DeliveryDecisions.after(entry(RequestType.ORDER), teq(TeqCode.OK), policy, now).status)
    }

    @Test
    fun `negative TEQ on a message counts as delivered (11_2_3_2)`() {
        val result = DeliveryDecisions.after(entry(RequestType.MESSAGE), teq(TeqCode.STRUCTURE_INVALID), policy, now)
        assertEquals(DeliveryStatus.DELIVERED_WITH_ERROR, result.status)
        assertEquals("0995", result.teqCode)
    }

    @Test
    fun `negative TEQ on an order means rejected`() {
        assertEquals(DeliveryStatus.REJECTED, DeliveryDecisions.after(entry(RequestType.ORDER), teq(TeqCode.VERSION_INVALID), policy, now).status)
    }

    @Test
    fun `TEQ 0991 overloaded is retried soon (KFT S_1_2_10)`() {
        val result = DeliveryDecisions.after(entry(RequestType.MESSAGE), teq(TeqCode.OVERLOADED), policy, now)
        assertEquals(DeliveryStatus.PENDING, result.status)
        assertEquals(now.plus(Duration.ofMinutes(1)), result.nextAttemptAt)
    }

    @Test
    fun `order without TEQ becomes unclear and is not resent automatically (11_2_3_1)`() {
        assertEquals(DeliveryStatus.UNCLEAR, DeliveryDecisions.after(entry(RequestType.ORDER), AttemptResult.NoAnswer("timeout"), policy, now).status)
    }

    @Test
    fun `message without TEQ is retried, then undeliverable`() {
        val retried = DeliveryDecisions.after(entry(RequestType.MESSAGE), AttemptResult.NoAnswer("timeout"), policy, now)
        assertEquals(DeliveryStatus.PENDING, retried.status)
        assertEquals(now.plus(Duration.ofMinutes(5)), retried.nextAttemptAt)
        assertEquals(DeliveryStatus.UNDELIVERABLE, DeliveryDecisions.after(entry(RequestType.MESSAGE, attempts = 2), AttemptResult.NoAnswer("timeout"), policy, now).status)
    }

    @Test
    fun `order that did not reach the partner may be retried with the same number`() {
        assertEquals(DeliveryStatus.PENDING, DeliveryDecisions.after(entry(RequestType.ORDER), AttemptResult.NotReached("connection refused"), policy, now).status)
    }
}
