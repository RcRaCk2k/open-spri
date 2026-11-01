package de.openspri.domain.event

import java.time.Instant

/** A stored answer to a request with an `Idempotency-Key` (AUFGABE 8.5). */
data class StoredResponse(val status: Int, val body: ByteArray, val contentType: String?)

sealed interface IdempotencyClaim {
    /** First use of the key: run the request, then [IdempotencyStore.complete] or [IdempotencyStore.release]. */
    data class Claimed(val id: Long) : IdempotencyClaim
    /** The same request was already answered: send the stored answer again. */
    data class Replay(val response: StoredResponse) : IdempotencyClaim
    /** The same key is still running in another request. */
    data object InProgress : IdempotencyClaim
    /** The key was used for a different request. */
    data object Mismatch : IdempotencyClaim
}

interface IdempotencyStore {
    fun claim(actor: String, key: String, requestSha256: String, now: Instant): IdempotencyClaim
    fun complete(id: Long, response: StoredResponse)
    /** The request failed before it changed anything (4xx/5xx): the key may be used again. */
    fun release(id: Long)
    fun purgeBefore(time: Instant): Int
}
