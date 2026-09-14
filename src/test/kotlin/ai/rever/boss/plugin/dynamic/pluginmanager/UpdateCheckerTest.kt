package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.impl.BlockedUpdate
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.UpdateCandidates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class UpdateCheckerTest {
    @Test
    fun `failure keeps cached updates and timestamp then retry clears error`() = runBlocking<Unit> {
        val state = MutableStateFlow(PluginManagerState())
        var response = Result.success(UpdateCandidates(mapOf("plugin" to "2"), listOf(BlockedUpdate("held", "3", "9.9"))))
        var timestamp = 10L
        val failures = mutableListOf<Exception>()
        val checker = UpdateChecker(state, { response }, { failures.add(it) }, { timestamp })
        checker.check()
        val cached = state.value
        assertEquals("2", cached.updates.single().newVersion)
        assertEquals("9.9", cached.blockedUpdates.single().requiredBossVersion)
        assertEquals(10L, cached.updatesLastChecked)
        response = Result.failure(IllegalStateException("request with secret details"))
        checker.check()
        assertEquals(cached.updates, state.value.updates)
        assertEquals(cached.blockedUpdates, state.value.blockedUpdates)
        assertEquals(10L, state.value.updatesLastChecked)
        assertFalse(state.value.isCheckingUpdates)
        assertFalse(state.value.updatesError!!.contains("secret"))
        assertEquals(1, failures.size)
        timestamp = 20L
        response = Result.success(UpdateCandidates())
        checker.check()
        assertNull(state.value.updatesError)
        assertTrue(state.value.updates.isEmpty())
        assertTrue(state.value.blockedUpdates.isEmpty())
        assertEquals(20L, state.value.updatesLastChecked)
    }

    @Test
    fun `overlapping refresh waits for prior check and keeps checking until completion`() = runBlocking<Unit> {
        val state = MutableStateFlow(PluginManagerState(updatesError = "previous failure"))
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var calls = 0
        val checker = UpdateChecker(state, {
            val call = ++calls
            if (call == 1) first.await() else second.await()
            Result.success(UpdateCandidates(mapOf("plugin" to call.toString())))
        }, { throw AssertionError(it) })
        val a = launch(start = CoroutineStart.UNDISPATCHED) { checker.check() }
        assertTrue(state.value.isCheckingUpdates)
        assertNull(state.value.updatesError)
        val b = launch(start = CoroutineStart.UNDISPATCHED) { checker.check() }
        assertEquals(1, calls, "a second request must not race the first response")
        first.complete(Unit)
        a.join()
        yield()
        assertEquals(2, calls)
        assertTrue(state.value.isCheckingUpdates)
        second.complete(Unit)
        b.join()
        assertFalse(state.value.isCheckingUpdates)
        assertEquals("2", state.value.updates.single().newVersion)
    }

    @Test
    fun `cancellation is propagated without a failure and releases lock`() = runBlocking<Unit> {
        val state = MutableStateFlow(PluginManagerState())
        var cancel = true
        val checker = UpdateChecker(state, {
            if (cancel) Result.failure(CancellationException("closed")) else Result.success(UpdateCandidates())
        }, { fail("cancellation must not be logged as failure") })
        assertFailsWith<CancellationException> { checker.check() }
        assertNull(state.value.updatesError)
        assertFalse(state.value.isCheckingUpdates)
        assertNull(state.value.updatesLastChecked)
        cancel = false
        checker.check()
        assertNotNull(state.value.updatesLastChecked)
    }

    @Test
    fun `thrown fetch exception is surfaced and finishes checking`() = runBlocking<Unit> {
        val state = MutableStateFlow(PluginManagerState())
        val checker = UpdateChecker(state, { throw IllegalStateException("offline") }, {})
        checker.check()
        assertNotNull(state.value.updatesError)
        assertFalse(state.value.isCheckingUpdates)
        assertNull(state.value.updatesLastChecked)
    }

    @Test
    fun `empty state only claims up to date after successful check`() {
        assertEquals("Updates haven't been checked yet", updatesEmptyState(false, null, null, false).message)
        assertEquals("Checking for updates…", updatesEmptyState(true, null, 1L, false).message)
        assertEquals("Couldn't check for updates", updatesEmptyState(false, "offline", 1L, false).message)
        assertEquals("No updates you can install yet", updatesEmptyState(false, null, 1L, true).message)
        assertEquals("All plugins are up to date", updatesEmptyState(false, null, 1L, false).message)
    }
}
