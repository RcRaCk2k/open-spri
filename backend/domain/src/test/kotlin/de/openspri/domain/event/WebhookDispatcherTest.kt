package de.openspri.domain.event

import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookDispatcherTest {

    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val events = (1L..4L).map { id ->
        BusinessEvent(id, if (id == 2L) EventType.DELIVERY_UPDATED else EventType.MESSAGE_RECEIVED, PartnerId(1), null, null, "A-$id", null, null, at = now)
    }
    private val feed = object : EventFeed {
        override fun after(id: Long, limit: Int, types: Set<EventType>?) = events.filter { it.id!! > id }.take(limit)
        override fun latestId() = events.maxOf { it.id!! }
        override fun purgeBefore(time: Instant) = 0
    }

    private class OneStore(var subscription: WebhookSubscription) : WebhookStore {
        override fun list() = listOf(subscription)
        override fun get(id: Long) = subscription
        override fun create(subscription: WebhookSubscription) = subscription
        override fun update(id: Long, url: String, types: Set<EventType>, enabled: Boolean, description: String?) = true
        override fun delete(id: Long) = true
        override fun deliverNext(now: Instant, deliver: (WebhookSubscription) -> WebhookSubscription): Boolean {
            if (subscription.nextAttemptAt.isAfter(now) || subscription.lastEventId >= 4) return false
            subscription = deliver(subscription)
            return true
        }
    }

    private fun subscription(types: Set<EventType> = emptySet()) =
        WebhookSubscription(1, "https://hook.example.invalid", ByteArray(0), types, true, null, 0, nextAttemptAt = now, createdAt = now)

    @Test
    fun `events are delivered in order and filtered by type`() {
        val store = OneStore(subscription(setOf(EventType.MESSAGE_RECEIVED)))
        val sent = mutableListOf<Long>()
        WebhookDispatcher(store, feed, { _, e -> sent += e.id!!; null }, clock).drain()
        assertEquals(listOf(1L, 3L, 4L), sent)
        assertEquals(4, store.subscription.lastEventId)
        assertEquals(now, store.subscription.lastSuccessAt)
    }

    @Test
    fun `a failing receiver keeps the cursor before the failed event and backs off`() {
        val store = OneStore(subscription())
        var fail = true
        val sent = mutableListOf<Long>()
        val dispatcher = WebhookDispatcher(
            store, feed,
            { _, e -> if (e.id == 3L && fail) "HTTP 500" else { sent += e.id!!; null } },
            clock,
        )
        assertTrue(dispatcher.dispatchNext())
        assertEquals(2, store.subscription.lastEventId)
        assertEquals(1, store.subscription.failures)
        assertEquals(now.plusSeconds(10), store.subscription.nextAttemptAt)
        assertEquals("Ereignis 3: HTTP 500", store.subscription.lastError)
        assertFalse(dispatcher.dispatchNext(), "not due before the backoff ends")

        fail = false
        store.subscription = store.subscription.copy(nextAttemptAt = now)
        dispatcher.drain()
        assertEquals(listOf(1L, 2L, 3L, 4L), sent)
        assertEquals(0, store.subscription.failures)
        assertNull(store.subscription.lastError)
    }

    @Test
    fun `backoff doubles up to one hour`() {
        val dispatcher = WebhookDispatcher(OneStore(subscription()), feed, { _, _ -> null }, clock)
        assertEquals(Duration.ofSeconds(10), dispatcher.backoff(1))
        assertEquals(Duration.ofSeconds(80), dispatcher.backoff(4))
        assertEquals(Duration.ofHours(1), dispatcher.backoff(30))
    }
}
