package de.openspri.domain.event

import java.time.Clock
import java.time.Duration

/**
 * Delivers events to webhook receivers in the order of the feed (D-14). A receiver gets one event per request;
 * if it fails (no 2xx), the same event is retried with exponential backoff (10 s doubling, at most
 * [maxBackoff]) and the following events wait. Events of types the subscription does not want are skipped.
 *
 * [post] sends one event and returns null on success or the error text.
 */
class WebhookDispatcher(
    private val store: WebhookStore,
    private val feed: EventFeed,
    private val post: (WebhookSubscription, BusinessEvent) -> String?,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 100,
    private val maxBackoff: Duration = Duration.ofHours(1),
) {
    /** Delivers pending events of one due subscription. Returns false if no subscription is due. */
    fun dispatchNext(): Boolean = store.deliverNext(clock.instant()) { subscription -> deliver(subscription) }

    fun drain(limit: Int = 100): Int {
        var count = 0
        while (count < limit && dispatchNext()) count++
        return count
    }

    private fun deliver(subscription: WebhookSubscription): WebhookSubscription {
        var cursor = subscription.lastEventId
        var delivered = false
        for (event in feed.after(cursor, batchSize)) {
            val id = requireNotNull(event.id)
            if (subscription.types.isNotEmpty() && event.type !in subscription.types) {
                cursor = id
                continue
            }
            val error = try {
                post(subscription, event)
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            if (error != null) {
                val failures = subscription.failures + 1
                return subscription.copy(
                    lastEventId = cursor,
                    failures = failures,
                    nextAttemptAt = clock.instant().plus(backoff(failures)),
                    lastError = "Ereignis $id: $error",
                    lastSuccessAt = if (delivered) clock.instant() else subscription.lastSuccessAt,
                )
            }
            cursor = id
            delivered = true
        }
        return subscription.copy(
            lastEventId = cursor,
            failures = 0,
            nextAttemptAt = clock.instant(),
            lastError = null,
            lastSuccessAt = if (delivered) clock.instant() else subscription.lastSuccessAt,
        )
    }

    internal fun backoff(failures: Int): Duration {
        val seconds = 10L shl (failures - 1).coerceIn(0, 20)
        return Duration.ofSeconds(seconds).coerceAtMost(maxBackoff)
    }
}
