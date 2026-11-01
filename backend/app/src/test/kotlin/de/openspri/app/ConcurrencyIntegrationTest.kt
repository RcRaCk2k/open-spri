package de.openspri.app

import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.delivery.RequestType
import de.openspri.domain.exchange.ArchivedExchange
import de.openspri.domain.exchange.Direction
import de.openspri.domain.exchange.InboundMessage
import de.openspri.domain.exchange.SoapOperation
import de.openspri.persistence.JdbcInboxQueue
import de.openspri.persistence.JdbcMessageArchive
import de.openspri.persistence.JdbcOutbox
import de.openspri.persistence.JdbcPartnerDirectory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mariadb.MariaDBContainer
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-instance guarantees of outbox and inbox on a real MariaDB (FOR UPDATE SKIP LOCKED): several workers
 * in parallel, every entry exactly once, strictly in order per key, never two in flight for the same key.
 * Skipped without Docker.
 */
@SpringBootTest(
    classes = [OpenSpriApplication::class],
    properties = ["openspri.inbox.initial-delay=1h", "openspri.outbox.initial-delay=1h"],
)
@Testcontainers(disabledWithoutDocker = true)
class ConcurrencyIntegrationTest(
    @Autowired private val outbox: JdbcOutbox,
    @Autowired private val inbox: JdbcInboxQueue,
    @Autowired private val archive: JdbcMessageArchive,
    @Autowired private val partners: JdbcPartnerDirectory,
) {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mariadb = MariaDBContainer("mariadb:11.8")
    }

    private val keys = listOf("A", "B", "C")
    private val perKey = 6

    private fun parallel(workers: Int, work: () -> Unit) {
        val pool = Executors.newFixedThreadPool(workers)
        repeat(workers) { pool.submit(work) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
    }

    @Test
    fun `outbox - exactly once, in order per key, one in flight per key`() {
        val partner = partners.create("Nebenläufig", "DEU.CONC", emptyList(), emptyList())
        val now = Instant.now()
        val ids = keys.associateWith { mutableListOf<Long>() }
        repeat(perKey) { _ ->
            keys.forEach { key ->
                ids.getValue(key) += outbox.enqueue(
                    OutboxEntry(null, partner, null, RequestType.MESSAGE, SoapOperation.ANNEHMEN_MELDUNG, key, "${partner.value}:$key",
                        "<x/>".toByteArray(), nextAttemptAt = now, createdAt = now),
                ).id!!
            }
        }
        val sent = Collections.synchronizedList(mutableListOf<OutboxEntry>())
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val overlaps = Collections.synchronizedList(mutableListOf<String>())

        parallel(6) {
            while (outbox.dispatchNext(Instant.now()) { entry ->
                    if (!inFlight.add(entry.sequenceKey)) overlaps += entry.sequenceKey
                    Thread.sleep(20)
                    sent += entry
                    inFlight.remove(entry.sequenceKey)
                    entry.copy(status = DeliveryStatus.DELIVERED, attempts = 1, finishedAt = Instant.now())
                }) { /* continue */ }
        }

        assertEquals(emptyList(), overlaps, "two requests of the same key were in flight at the same time")
        assertEquals(keys.size * perKey, sent.size)
        assertEquals(sent.size, sent.map { it.id }.toSet().size, "an entry was sent twice")
        keys.forEach { key -> assertEquals(ids.getValue(key), sent.filter { it.sequenceKey.endsWith(":$key") }.map { it.id }) }
    }

    @Test
    fun `inbox - exactly once, in order per order, in parallel across orders`() {
        val partner = partners.create("Eingang", "DEU.INBOX", emptyList(), emptyList())
        repeat(perKey) { index ->
            keys.forEach { key ->
                val exchange = ArchivedExchange(Direction.INBOUND, SoapOperation.ANNEHMEN_MELDUNG, partner, "127.0.0.1", Instant.now(), 1,
                    "<r/>".toByteArray(), null, null, null, null, null, key)
                archive.storeAccepted(exchange, InboundMessage(partner, SoapOperation.ANNEHMEN_MELDUNG, key, "<m n=\"$index\"/>".toByteArray()))
            }
        }
        val processed = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val inFlight = ConcurrentHashMap.newKeySet<String>()
        val overlaps = Collections.synchronizedList(mutableListOf<String>())

        parallel(6) {
            while (inbox.processNext { item ->
                    val key = item.message.externalOrderNumber!!
                    if (!inFlight.add(key)) overlaps += key
                    Thread.sleep(20)
                    processed += key to String(item.message.payload)
                    inFlight.remove(key)
                    emptyList()
                }) { /* continue */ }
        }

        assertEquals(emptyList(), overlaps)
        assertEquals(keys.size * perKey, processed.size)
        keys.forEach { key ->
            assertEquals((0 until perKey).map { "<m n=\"$it\"/>" }, processed.filter { it.first == key }.map { it.second })
        }
    }

}
