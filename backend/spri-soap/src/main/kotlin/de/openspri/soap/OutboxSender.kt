package de.openspri.soap

import de.openspri.domain.delivery.AttemptResult
import de.openspri.domain.delivery.OutboxEntry
import de.openspri.domain.partner.PartnerDirectory
import java.net.URI

/** Transport for the outbox: sends one entry with [SpriClient] and translates the outcome. */
class OutboxSender(private val partners: PartnerDirectory, private val client: SpriClient) : (OutboxEntry) -> AttemptResult {

    override fun invoke(entry: OutboxEntry): AttemptResult {
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
