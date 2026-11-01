package de.openspri.domain.delivery

import de.openspri.domain.InMemoryOutageRegistry
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.RecordingNotifier
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DeliveryServiceTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private val clock = MutableClock(Instant.parse("2026-11-01T10:00:00Z"))
    private val outbox = InMemoryOutbox()
    private val outages = InMemoryOutageRegistry()
    private val notifier = RecordingNotifier()
    private val sent = mutableListOf<Long>()
    private var answer: (OutboxEntry) -> AttemptResult = { AttemptResult.Acknowledged(TeqCode.OK, "OK", "OK.") }
    private val service = DeliveryService(
        outbox, { entry -> sent += entry.id!!; answer(entry) }, outages, notifier,
        RetryPolicy(interval = Duration.ofMinutes(5), maxAttempts = 2), Duration.ofDays(1), clock,
    )

    private fun enqueue(key: String, partner: Long = 1) = outbox.enqueue(
        OutboxEntry(null, PartnerId(partner), null, RequestType.MESSAGE, SoapOperation.ANNEHMEN_MELDUNG, "A", key, ByteArray(0),
            nextAttemptAt = clock.now, createdAt = clock.now),
    )

    @Test
    fun `strict order per key, keys independent of each other (NF-03)`() {
        val a1 = enqueue("A"); val b1 = enqueue("B"); val a2 = enqueue("A")
        answer = { if (it.id == a1.id) AttemptResult.NotReached("down") else AttemptResult.Acknowledged(TeqCode.OK, "OK", "OK.") }
        service.drain()
        // a1 failed and waits for its retry: a2 must not overtake it, b1 is independent
        assertEquals(listOf(a1.id, b1.id), sent)
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        answer = { AttemptResult.Acknowledged(TeqCode.OK, "OK", "OK.") }
        service.drain()
        assertEquals(listOf(a1.id, b1.id, a1.id, a2.id), sent)
    }

    @Test
    fun `undeliverable entry blocks its key and is notified`() {
        val a1 = enqueue("A"); val a2 = enqueue("A")
        answer = { AttemptResult.NotReached("down") }
        service.drain()
        clock.now = clock.now.plus(Duration.ofMinutes(5))
        service.drain()
        assertEquals(DeliveryStatus.UNDELIVERABLE, outbox.entries.first { it.id == a1.id }.status)
        assertEquals(listOf(a1.id, a1.id), sent, "a2 must wait behind the undeliverable a1")
        assertEquals(a1.id, notifier.undeliverable.single().id)

        // operator sends a1 over the e-mail channel: the key is released
        outbox.markSentByEmail(a1.id!!, clock.now)
        answer = { AttemptResult.Acknowledged(TeqCode.OK, "OK", "OK.") }
        service.drain()
        assertEquals(DeliveryStatus.DELIVERED, outbox.entries.first { it.id == a2.id }.status)
    }

    @Test
    fun `outage is notified once per day and reset by a successful request (11_2_3_2)`() {
        answer = { AttemptResult.NotReached("down") }
        enqueue("A"); enqueue("B"); service.drain()
        assertEquals(1, notifier.outages.size, "two failures on the same day: one notification")
        clock.now = clock.now.plus(Duration.ofDays(1))
        enqueue("C"); service.drain()
        assertEquals(2, notifier.outages.size)

        answer = { AttemptResult.Acknowledged(TeqCode.OK, "OK", "OK.") }
        enqueue("D"); service.drain()
        answer = { AttemptResult.NotReached("down again") }
        enqueue("E"); service.drain()
        assertEquals(3, notifier.outages.size, "a new outage after a success is notified again")
    }
}
