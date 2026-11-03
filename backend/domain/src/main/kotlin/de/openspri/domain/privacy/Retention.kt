package de.openspri.domain.privacy

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What a retention run removed (NF-10). */
data class PurgeResult(
    /** Archived SOAP requests whose content was removed (metadata and SHA-256 remain). */
    val archivedRequests: Int = 0,
    val inboxPayloads: Int = 0,
    val outboxPayloads: Int = 0,
    /** Order drafts deleted. */
    val drafts: Int = 0,
    /** Completed orders whose address key (R-10) was removed. */
    val orders: Int = 0,
) {
    val total: Int get() = archivedRequests + inboxPayloads + outboxPayloads + drafts + orders
}

/**
 * Removes end customer data (names, addresses, contacts, owners) of completed business (NF-10, ADR-0010).
 *
 * Business is complete when it belongs to no order that is still open or was finished at or after [cutoff].
 * Removed are the message contents in archive, inbox and outbox, drafts not changed since [cutoff] and the
 * normalized address of completed orders. Kept are all metadata (time, direction, partner, numbers, TEQ, status)
 * and the SHA-256 of every archived request, so the archive stays verifiable (NF-09).
 */
interface PersonalDataStore {
    fun purgeBefore(cutoff: Instant, now: Instant): PurgeResult
}

/**
 * The retention rule: [retention] after completion, end customer data is removed. A zero or negative period
 * disables it (default: the operator decides, see docs/betrieb.md).
 */
class RetentionService(private val store: PersonalDataStore, private val retention: Duration, private val clock: Clock = Clock.systemUTC()) {

    val enabled: Boolean get() = retention > Duration.ZERO

    /** Runs the purge; null if retention is disabled. */
    fun run(): PurgeResult? {
        if (!enabled) return null
        val now = clock.instant()
        return store.purgeBefore(now.minus(retention), now)
    }
}
