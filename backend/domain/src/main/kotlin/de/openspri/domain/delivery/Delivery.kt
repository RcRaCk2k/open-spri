package de.openspri.domain.delivery

import de.openspri.domain.exchange.SoapOperation
import de.openspri.domain.exchange.TeqCode
import de.openspri.domain.order.OrderId
import de.openspri.domain.partner.PartnerId
import java.time.Duration
import java.time.Instant

/**
 * Status of an outbound request in the outbox (AUFGABE chapter 8.3, specification 11.2.3).
 *
 * Final statuses release the sequence key, so that the next request of the same key can be sent.
 * [UNDELIVERABLE] and [UNCLEAR] block the key until an operator decides (ordering is mandatory, 11.2.3.3).
 */
enum class DeliveryStatus(val final: Boolean, val blocksKey: Boolean) {
    PENDING(final = false, blocksKey = true),
    /** Positive TEQ. */
    DELIVERED(final = true, blocksKey = false),
    /** Message with negative TEQ: counts as delivered (11.2.3.2), listed in the error list. */
    DELIVERED_WITH_ERROR(final = true, blocksKey = false),
    /** Order with negative TEQ: the partner did not accept it. */
    REJECTED(final = true, blocksKey = false),
    /** Order without TEQ: may have been processed; never resent automatically with the same number (11.2.3.1). */
    UNCLEAR(final = false, blocksKey = true),
    /** Retries exhausted (11.2.3.2): manual resend or alternative e-mail channel. */
    UNDELIVERABLE(final = false, blocksKey = true),
    /** Sent manually over the alternative e-mail channel (11.2.3.2). */
    SENT_BY_EMAIL(final = true, blocksKey = false),
    /** Withdrawn by an operator. */
    CANCELLED(final = true, blocksKey = false),
}

/** What kind of request: orders and messages are treated differently on missing TEQ. */
enum class RequestType { ORDER, MESSAGE }

data class OutboxEntry(
    val id: Long?,
    val partnerId: PartnerId,
    val orderId: OrderId?,
    val type: RequestType,
    val operation: SoapOperation,
    val externalOrderNumber: String?,
    /** Ordering key: partner + LineID if known, else partner + external order number (AUFGABE 8.3). */
    val sequenceKey: String,
    /** The unsigned payload element (annehmenAuftragRequest / annehmenMeldungRequest), signed when sent. */
    val payload: ByteArray,
    val status: DeliveryStatus = DeliveryStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Instant,
    val lastError: String? = null,
    val teqCode: String? = null,
    val createdAt: Instant,
    val finishedAt: Instant? = null,
) {
    companion object {
        fun sequenceKey(partnerId: PartnerId, lineId: String?, externalOrderNumber: String?): String = when {
            !lineId.isNullOrBlank() -> "${partnerId.value}:LINE:$lineId"
            !externalOrderNumber.isNullOrBlank() -> "${partnerId.value}:ORDER:$externalOrderNumber"
            else -> "${partnerId.value}:SINGLE:${java.util.UUID.randomUUID()}"
        }
    }
}

/** Result of one send attempt, as reported by the transport (SpriClient). */
sealed interface AttemptResult {
    data class Acknowledged(val teq: TeqCode?, val code: String, val text: String) : AttemptResult

    /** The request did certainly not reach the partner (e.g. connection refused, unknown host). */
    data class NotReached(val reason: String) : AttemptResult

    /** It is unknown whether the partner received the request (e.g. timeout while waiting for the TEQ). */
    data class NoAnswer(val reason: String) : AttemptResult

    /** Not sent because our own request is invalid (e.g. Anlage 1 in enforce mode). */
    data class Invalid(val reason: String) : AttemptResult
}

/** Retry rules (specification 11.2.3.2: "in regelmäßigen Abständen mehrmals wiederholt"). */
data class RetryPolicy(
    val interval: Duration = Duration.ofMinutes(5),
    val maxAttempts: Int = 12,
    /** TEQ 0991 (interface overloaded): wait shorter, then send again (KFT S.1.2.10). */
    val overloadedInterval: Duration = Duration.ofMinutes(1),
)

/** The decision after an attempt: new status and when to try again. Pure function, see [DeliveryDecisionsTest]. */
object DeliveryDecisions {

    fun after(entry: OutboxEntry, result: AttemptResult, policy: RetryPolicy, now: Instant): OutboxEntry {
        val attempts = entry.attempts + 1
        val base = entry.copy(attempts = attempts)
        return when (result) {
            is AttemptResult.Acknowledged -> when {
                result.teq == TeqCode.OK -> base.finish(DeliveryStatus.DELIVERED, now, result.code, null)
                result.teq == TeqCode.OVERLOADED -> base.retry(policy, now, policy.overloadedInterval, "TEQ ${result.code}: ${result.text}", result.code)
                entry.type == RequestType.MESSAGE -> base.finish(DeliveryStatus.DELIVERED_WITH_ERROR, now, result.code, "TEQ ${result.code}: ${result.text}")
                else -> base.finish(DeliveryStatus.REJECTED, now, result.code, "TEQ ${result.code}: ${result.text}")
            }
            is AttemptResult.NotReached -> base.retry(policy, now, policy.interval, result.reason, null)
            is AttemptResult.NoAnswer -> when (entry.type) {
                // 11.2.3.1: the Auftraggeber may resend with a NEW order number - an operator decision
                RequestType.ORDER -> base.copy(status = DeliveryStatus.UNCLEAR, lastError = result.reason)
                RequestType.MESSAGE -> base.retry(policy, now, policy.interval, result.reason, null)
            }
            is AttemptResult.Invalid -> base.copy(status = DeliveryStatus.UNDELIVERABLE, lastError = result.reason)
        }
    }

    private fun OutboxEntry.finish(status: DeliveryStatus, now: Instant, teq: String?, error: String?) =
        copy(status = status, teqCode = teq, lastError = error, finishedAt = now)

    private fun OutboxEntry.retry(policy: RetryPolicy, now: Instant, wait: Duration, error: String, teq: String?) =
        if (attempts >= policy.maxAttempts) copy(status = DeliveryStatus.UNDELIVERABLE, lastError = error, teqCode = teq)
        else copy(status = DeliveryStatus.PENDING, nextAttemptAt = now.plus(wait), lastError = error, teqCode = teq)
}

/**
 * The outbox. Entries are written in the same transaction as the business change that causes them
 * (transactional outbox) and sent strictly in order per sequence key.
 */
interface Outbox {
    fun enqueue(entry: OutboxEntry): OutboxEntry

    /**
     * Sends the next due entry whose key is not blocked by an earlier entry: claims it, runs [send] and stores
     * the result atomically. Several instances may call this concurrently. Returns false if nothing is due.
     */
    fun dispatchNext(now: Instant, send: (OutboxEntry) -> OutboxEntry): Boolean

    /** Operator actions (delivery monitor, M4). */
    fun retry(id: Long, now: Instant): Boolean
    fun markSentByEmail(id: Long, now: Instant): Boolean
    fun cancel(id: Long, now: Instant): Boolean
}

/** Partner interface outage (11.2.3.2): notify at most once per day and outage. */
data class PartnerOutage(val partnerId: PartnerId, val since: Instant, val lastNotifiedAt: Instant?)

interface OutageRegistry {
    /** Records a failed attempt. Returns the outage if a notification is due now. */
    fun failed(partnerId: PartnerId, now: Instant, notifyEvery: Duration): PartnerOutage?

    /** A successful request ends the outage; the next failure starts a new one (and a new notification). */
    fun succeeded(partnerId: PartnerId)
}

/** Sends notifications (e-mail). Implementations must never throw into the delivery. */
interface Notifier {
    fun partnerInterfaceDown(partnerId: PartnerId, outage: PartnerOutage, lastError: String?)
    fun undeliverable(entry: OutboxEntry)
}
