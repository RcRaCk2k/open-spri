package de.openspri.domain.delivery

import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventLog
import de.openspri.domain.partner.PartnerId
import java.time.Clock
import java.time.Duration

/**
 * Sends due outbox entries (AUFGABE chapter 8.3): one attempt per call of [dispatchNext], the decision about
 * the next step comes from [DeliveryDecisions]. Partner outages are tracked and notified at most once per
 * [outageNotificationInterval] (specification 11.2.3.2).
 */
class DeliveryService(
    private val outbox: Outbox,
    private val send: (OutboxEntry) -> AttemptResult,
    private val outages: OutageRegistry,
    private val notifier: Notifier,
    private val policy: RetryPolicy = RetryPolicy(),
    private val outageNotificationInterval: Duration = Duration.ofDays(1),
    private val clock: Clock = Clock.systemUTC(),
    private val events: EventLog = EventLog.NONE,
) {

    fun dispatchNext(): Boolean = outbox.dispatchNext(clock.instant()) { entry ->
        val result = try {
            send(entry)
        } catch (e: Exception) {
            AttemptResult.NoAnswer("${e.javaClass.simpleName}: ${e.message}")
        }
        val updated = DeliveryDecisions.after(entry, result, policy, clock.instant())
        track(entry.partnerId, result, updated)
        if (updated.status == DeliveryStatus.UNDELIVERABLE) safely { notifier.undeliverable(updated) }
        // same transaction as the status change (dispatchNext holds it while sending)
        if (updated.status != entry.status) events.publish(BusinessEvent.of(updated, clock.instant()))
        updated
    }

    /** Sends until nothing is due; returns the number of attempts. */
    fun drain(limit: Int = 1000): Int {
        var count = 0
        while (count < limit && dispatchNext()) count++
        return count
    }

    private fun track(partnerId: PartnerId, result: AttemptResult, updated: OutboxEntry) {
        when (result) {
            is AttemptResult.Acknowledged -> outages.succeeded(partnerId)
            is AttemptResult.NotReached, is AttemptResult.NoAnswer ->
                outages.failed(partnerId, clock.instant(), outageNotificationInterval)?.let { outage ->
                    safely { notifier.partnerInterfaceDown(partnerId, outage, updated.lastError) }
                }
            is AttemptResult.Invalid -> Unit
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // notifications must never break the delivery; the notifier logs its own errors
        }
    }
}
