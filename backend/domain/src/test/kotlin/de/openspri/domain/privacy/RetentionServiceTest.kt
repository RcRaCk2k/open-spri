package de.openspri.domain.privacy

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RetentionServiceTest {

    private val now = Instant.parse("2027-06-30T02:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class RecordingStore : PersonalDataStore {
        val calls = mutableListOf<Pair<Instant, Instant>>()
        override fun purgeBefore(cutoff: Instant, now: Instant): PurgeResult {
            calls += cutoff to now
            return PurgeResult(archivedRequests = 3, drafts = 1)
        }
    }

    @Test
    fun `purges everything completed before now minus the retention period`() {
        val store = RecordingStore()
        val result = RetentionService(store, Duration.ofDays(365), clock).run()
        assertEquals(listOf(Instant.parse("2026-06-30T02:30:00Z") to now), store.calls)
        assertEquals(4, result!!.total)
    }

    @Test
    fun `zero retention means disabled and touches nothing`() {
        val store = RecordingStore()
        val service = RetentionService(store, Duration.ZERO, clock)
        assertFalse(service.enabled)
        assertNull(service.run())
        assertEquals(0, store.calls.size)
    }
}
