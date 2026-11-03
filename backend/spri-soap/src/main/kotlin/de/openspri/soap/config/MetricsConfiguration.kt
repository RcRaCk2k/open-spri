package de.openspri.soap.config

import de.openspri.domain.delivery.DeliveryStatus
import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.query.DeliveryQueries
import de.openspri.domain.query.InboxQueries
import de.openspri.soap.SpriMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Prometheus metrics of the S/PRI interface (NF-11), published under `/metrics`:
 *
 * | Metric                                   | Tags                                  | Meaning                                         |
 * |------------------------------------------|---------------------------------------|-------------------------------------------------|
 * | `openspri_inbound_requests_seconds`      | partner, operation, teq               | time from receipt to TEQ (NF-01, target p95 < 1 s) |
 * | `openspri_outbound_attempts_seconds`     | partner, operation, result, teq       | send attempts and their outcome (error rate)    |
 * | `openspri_outbox_messages`               | status                                | queue depth of the outbox                       |
 * | `openspri_inbox_messages`                | status                                | queue depth of the inbox                        |
 *
 * `partner` is the partner id (`none` if the address belongs to no partner), `teq` the TEQ code or `forbidden`.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {

    @Bean
    fun spriMetrics(registry: ObjectProvider<MeterRegistry>): SpriMetrics =
        registry.ifAvailable?.let(::MicrometerSpriMetrics) ?: SpriMetrics.NONE

    @Bean
    fun queueMetrics(deliveries: DeliveryQueries, inbox: InboxQueries) = QueueMetrics(deliveries, inbox)
}

class MicrometerSpriMetrics(private val registry: MeterRegistry) : SpriMetrics {

    override fun inbound(partnerId: Long?, operation: SoapOperation?, teq: String, duration: Duration) {
        Timer.builder("openspri.inbound.requests")
            .description("Inbound S/PRI requests: time from receipt to the TEQ")
            .tag("partner", partnerId?.toString() ?: "none")
            .tag("operation", operation?.wsdlName ?: "unknown")
            .tag("teq", teq)
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30))
            .register(registry)
            .record(duration)
    }

    override fun outbound(partnerId: Long, operation: SoapOperation, result: String, teq: String?, duration: Duration) {
        Timer.builder("openspri.outbound.attempts")
            .description("Outbound S/PRI send attempts and their outcome")
            .tag("partner", partnerId.toString())
            .tag("operation", operation.wsdlName)
            .tag("result", result)
            .tag("teq", teq ?: "none")
            .register(registry)
            .record(duration)
    }
}

/** Queue depth of outbox and inbox. The counts are cached briefly so that a scrape costs two queries at most. */
class QueueMetrics(private val deliveries: DeliveryQueries, private val inbox: InboxQueries) : MeterBinder {

    private val cache = AtomicReference<Snapshot?>(null)

    private class Snapshot(val at: Long, val outbox: Map<DeliveryStatus, Long>, val inbox: Map<String, Long>)

    override fun bindTo(registry: MeterRegistry) {
        DeliveryStatus.entries.forEach { status ->
            Gauge.builder("openspri.outbox.messages") { snapshot().outbox[status] ?: 0L }
                .description("Outbox entries per status").tag("status", status.name).register(registry)
        }
        INBOX_STATUSES.forEach { status ->
            Gauge.builder("openspri.inbox.messages") { snapshot().inbox[status] ?: 0L }
                .description("Inbox items per status").tag("status", status).register(registry)
        }
    }

    private fun snapshot(): Snapshot {
        val now = System.nanoTime()
        cache.get()?.takeIf { now - it.at < MAX_AGE_NANOS }?.let { return it }
        return Snapshot(now, deliveries.countByStatus(), inbox.countByStatus()).also(cache::set)
    }

    private companion object {
        val INBOX_STATUSES = listOf("RECEIVED", "PROCESSED", "FAILED")
        val MAX_AGE_NANOS = Duration.ofSeconds(5).toNanos()
    }
}
