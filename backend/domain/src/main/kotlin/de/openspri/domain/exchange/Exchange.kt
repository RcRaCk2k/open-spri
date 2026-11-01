package de.openspri.domain.exchange

import de.openspri.domain.partner.PartnerId
import java.time.Instant

enum class Direction { INBOUND, OUTBOUND }

/** The two operations of the S/PRI WSDL (specification chapter 3, Anlage 3 spri.wsdl). */
enum class SoapOperation(val wsdlName: String) {
    ANNEHMEN_AUFTRAG("annehmenAuftrag"),
    ANNEHMEN_MELDUNG("annehmenMeldung"),
    ;

    companion object {
        fun fromWsdlName(name: String): SoapOperation? = entries.firstOrNull { it.wsdlName == name }
    }
}

/**
 * One request/response pair exchanged over the S/PRI interface, archived verbatim
 * (NF-09: revision-proof message archive). [request] and [response] are the exact bytes on the wire.
 */
data class ArchivedExchange(
    val direction: Direction,
    val operation: SoapOperation?,
    val partnerId: PartnerId?,
    val remoteAddress: String,
    val startedAt: Instant,
    val durationMillis: Long,
    val request: ByteArray,
    val response: ByteArray?,
    val teqCode: TeqCode?,
    /** Internal reason for a negative result; never sent to the partner. */
    val rejectionReason: String?,
    val signerSubject: String?,
    val signerSerial: String?,
    val externalOrderNumber: String?,
    /** Observations that did not lead to a rejection, e.g. Anlage 1 violations in report mode. */
    val findings: String? = null,
)

/** A validated inbound order or message, handed over to business processing (M2). */
data class InboundMessage(
    val partnerId: PartnerId,
    val operation: SoapOperation,
    val externalOrderNumber: String?,
    /** The schema-valid body payload (annehmenAuftragRequest / annehmenMeldungRequest) as XML. */
    val payload: ByteArray,
)

/** Persists inbound exchanges. Accepted messages are archived and queued in one transaction. */
interface InboundStore {

    /** Archives the exchange and queues [message] atomically. Returns the archive id. */
    fun storeAccepted(exchange: ArchivedExchange, message: InboundMessage): Long

    /** Archives a rejected exchange. Returns the archive id. */
    fun storeRejected(exchange: ArchivedExchange): Long
}

/** Persists outbound exchanges. */
interface OutboundArchive {
    fun store(exchange: ArchivedExchange): Long
}
