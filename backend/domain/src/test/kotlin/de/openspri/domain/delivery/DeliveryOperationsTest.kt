package de.openspri.domain.delivery

import de.openspri.domain.DirectTransactions
import de.openspri.domain.InMemoryEvents
import de.openspri.domain.InMemoryOutbox
import de.openspri.domain.event.EventType
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.partner.PartnerId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DeliveryOperationsTest {

    private val outbox = InMemoryOutbox()
    private val events = InMemoryEvents()
    private val operations = DeliveryOperations(outbox, outbox, DirectTransactions, events)

    private fun entry(type: RequestType, status: DeliveryStatus) = outbox.enqueue(
        OutboxEntry(
            null, PartnerId(1), null, type,
            if (type == RequestType.ORDER) SoapOperation.ANNEHMEN_AUFTRAG else SoapOperation.ANNEHMEN_MELDUNG,
            "A-1", "k", ByteArray(0), status = status, nextAttemptAt = Instant.now(), createdAt = Instant.now(),
        ),
    )

    @Test
    fun `an unclear order is never resent with the same number (NF-05)`() {
        val order = entry(RequestType.ORDER, DeliveryStatus.UNCLEAR)
        assertContains(assertThrows<DeliveryActionRejected> { operations.retry(order.id!!) }.message!!, "NF-05")
        assertEquals(DeliveryStatus.CANCELLED, operations.cancel(order.id!!).status)
        assertEquals(EventType.DELIVERY_UPDATED, events.events.single().type)
        assertEquals(DeliveryStatus.CANCELLED, events.events.single().deliveryStatus)
    }

    @Test
    fun `undeliverable entries and unclear messages may be resent`() {
        assertEquals(DeliveryStatus.PENDING, operations.retry(entry(RequestType.ORDER, DeliveryStatus.UNDELIVERABLE).id!!).status)
        assertEquals(DeliveryStatus.PENDING, operations.retry(entry(RequestType.MESSAGE, DeliveryStatus.UNCLEAR).id!!).status)
        assertThrows<DeliveryActionRejected> { operations.retry(entry(RequestType.MESSAGE, DeliveryStatus.DELIVERED).id!!) }
        assertThrows<NoSuchElementException> { operations.cancel(999) }
    }
}
