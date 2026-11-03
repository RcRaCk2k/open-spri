package de.openspri.soap

import de.openspri.domain.delivery.AttemptResult
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.partner.PartnerDirectory
import java.net.URI
import java.time.Duration

/** Transport for the outbox: sends one entry with [SpriClient] and translates the outcome. */
class OutboxSender(
    private val partners: PartnerDirectory,
    private val client: SpriClient,
    private val metrics: SpriMetrics = SpriMetrics.NONE,
) : (OutboxEntry) -> AttemptResult {

    override fun invoke(entry: OutboxEntry): AttemptResult {
        val started = System.nanoTime()
        val result = attempt(entry)
        val (name, teq) = when (result) {
            is AttemptResult.Acknowledged -> "acknowledged" to result.code
            is AttemptResult.NoAnswer -> "no_answer" to null
            is AttemptResult.NotReached -> "not_reached" to null
            is AttemptResult.Invalid -> "invalid" to null
        }
        metrics.outbound(entry.partnerId.value, entry.operation, name, teq, Duration.ofNanos(System.nanoTime() - started))
        return result
    }

    private fun attempt(entry: OutboxEntry): AttemptResult {
        val partner = partners.findById(entry.partnerId) ?: return AttemptResult.NotReached("Partner ${entry.partnerId.value} ist nicht (mehr) aktiv")
        val endpoint = partner.endpointUrl?.takeIf { it.isNotBlank() }
            ?: return AttemptResult.Invalid("Für Partner ${partner.name} ist kein S/PRI-Endpunkt hinterlegt")
        val payload = SecureXml.parse(entry.payload).documentElement
        return when (val outcome = client.send(partner, URI(endpoint), entry.operation, payload, entry.externalOrderNumber)) {
            is SpriClient.Outcome.Acknowledged -> AttemptResult.Acknowledged(outcome.teq, outcome.teqCode, outcome.teqText)
            is SpriClient.Outcome.NotAcknowledged ->
                if (outcome.reached) AttemptResult.NoAnswer(outcome.reason) else AttemptResult.NotReached(outcome.reason)
            is SpriClient.Outcome.NotSent -> AttemptResult.Invalid("Anlage 1: " + outcome.violations.joinToString(" | "))
        }
    }
}
