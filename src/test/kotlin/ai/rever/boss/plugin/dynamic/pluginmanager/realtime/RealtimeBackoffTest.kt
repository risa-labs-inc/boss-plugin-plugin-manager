package ai.rever.boss.plugin.dynamic.pluginmanager.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [backoffMillis], the setup-retry schedule in [PluginStoreRealtimeClient].
 *
 * A top-level function rather than a private method so the schedule is testable without
 * constructing the client. The two things worth pinning are the ones a refactor breaks
 * silently: the `shl` must be clamped (shifting a Long by 64 wraps back to the base delay
 * rather than saturating, which would turn the cap into a busy loop), and the sequence must
 * start at the base rather than at double it, which an off-by-one in the 1-based `attempt`
 * would cause.
 */
class RealtimeBackoffTest {
    @Test
    fun `backoff doubles from the base and caps at 30s`() {
        assertEquals(1_000L, backoffMillis(1))
        assertEquals(2_000L, backoffMillis(2))
        assertEquals(4_000L, backoffMillis(3))
        assertEquals(8_000L, backoffMillis(4))
        assertEquals(16_000L, backoffMillis(5))
        assertEquals(30_000L, backoffMillis(6))
    }

    @Test
    fun `backoff stays capped for absurd attempt counts`() {
        // The clamp is the point: an unclamped `1000L shl 64` is 1000L again, so a caller past
        // the cap would retry every second forever instead of every 30.
        for (attempt in intArrayOf(7, 32, 64, 65, 1_000, Int.MAX_VALUE)) {
            assertEquals(30_000L, backoffMillis(attempt), "attempt=$attempt")
        }
    }

    @Test
    fun `backoff is never zero or negative for any attempt`() {
        // attempt is documented 1-based; a 0 or negative caller must still yield a real delay
        // rather than a tight retry loop.
        for (attempt in intArrayOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertTrue(backoffMillis(attempt) >= 1_000L, "attempt=$attempt")
        }
    }
}

/**
 * Unit tests for [shouldResync], the "realtime came back" edge behind the catalog catch-up.
 *
 * The rule is subtle in exactly the way that costs a redundant network fetch on every panel open,
 * and the distinction it turns on - no observation yet, versus observed disconnected - is invisible
 * in a Boolean. Pinning it here is cheaper than rediscovering it from a duplicated request log.
 *
 * It suppresses the duplicate for a panel attaching to an already-connected client, which is the
 * common case, and not for one attaching mid-connect: `isConnected` starts false, and combining
 * two channel statuses means an intermediate false is normal even on a healthy first connect,
 * since one channel reaches SUBSCRIBED before the other. That startup case still costs one extra
 * fetch. Erring that way is deliberate - the same emission pattern is how an app that started
 * offline catches up, and suppressing it would trade a redundant fetch for a missed one.
 */
class ShouldResyncTest {
    @Test
    fun `a first emission of connected is not a reconnect`() {
        // Realtime connects at plugin load, so a panel opening later sees true first. Treating
        // that as an edge duplicates the initial load's own fetch every time a panel opens.
        assertFalse(shouldResync(previous = null, connected = true))
    }

    @Test
    fun `connected after disconnected is a reconnect`() {
        assertTrue(shouldResync(previous = false, connected = true))
    }

    @Test
    fun `staying connected is not a reconnect`() {
        // StateFlow conflates duplicates, but a re-collect can still replay the current value.
        assertFalse(shouldResync(previous = true, connected = true))
    }

    @Test
    fun `no disconnected emission ever triggers a resync`() {
        for (previous in arrayOf(null, false, true)) {
            assertFalse(shouldResync(previous, connected = false), "previous=$previous")
        }
    }
}
