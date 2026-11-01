package de.openspri.domain.delivery

import de.openspri.domain.event.BusinessEvent
import de.openspri.domain.event.EventLog
import de.openspri.domain.order.Transactions
import de.openspri.domain.query.DeliveryQueries
import java.time.Clock

/** The entry does not exist or its status does not allow the action. REST: 404/409. */
class DeliveryActionRejected(message: String) : RuntimeException(message)

/** Operator actions on the outbox (AUFGABE 5.7, 11.2.3.2); each status change is also an event. */
class DeliveryOperations(
    private val outbox: Outbox,
    private val deliveries: DeliveryQueries,
    private val transactions: Transactions,
    private val events: EventLog = EventLog.NONE,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /**
     * Only UNDELIVERABLE, and UNCLEAR messages. An UNCLEAR order may have arrived: it is never sent again with
     * the same number (NF-05, 11.2.3.1); the operator places it again with a new number and cancels this entry.
     */
    fun retry(id: Long): OutboxEntry = act(id) { entry ->
        if (entry.status == DeliveryStatus.UNCLEAR && entry.type == RequestType.ORDER) {
            throw DeliveryActionRejected(
                "Ein Auftrag ohne technische Quittung wird nicht mit derselben Auftragsnummer erneut gesendet (NF-05, 11.2.3.1). " +
                    "Beim Partner klären, dann mit neuer Auftragsnummer neu stellen und diesen Eintrag zurückziehen.",
            )
        }
        outbox.retry(id, clock.instant())
    }

    /** Sent over the alternative e-mail channel (11.2.3.2); only UNDELIVERABLE and UNCLEAR. */
    fun markSentByEmail(id: Long): OutboxEntry = act(id) { _ -> outbox.markSentByEmail(id, clock.instant()) }

    /** PENDING, UNDELIVERABLE and UNCLEAR; releases the following entries of the same ordering key. */
    fun cancel(id: Long): OutboxEntry = act(id) { _ -> outbox.cancel(id, clock.instant()) }

    private fun act(id: Long, change: (OutboxEntry) -> Boolean): OutboxEntry = transactions.run {
        val before = deliveries.get(id) ?: throw NoSuchElementException("Nachricht $id existiert nicht")
        if (!change(before)) throw DeliveryActionRejected("Im Status ${before.status} nicht möglich")
        val after = requireNotNull(deliveries.get(id))
        events.publish(BusinessEvent.of(after, clock.instant()))
        after
    }
}
