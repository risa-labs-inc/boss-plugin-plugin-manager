package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What this plugin kept when its own progress widget moved into the host: the
 * display names it alone knows, the kind mapping, and the two facts about a
 * button that made the hand-off worth doing.
 */
class DownloadCenterHandoffTest {
    @Test
    fun `a hinted name is used once and then forgotten`() {
        val names = DownloadDisplayNames()
        names.hint("ai.rever.boss.plugin.dynamic.docker", "Docker")

        assertEquals("Docker", names.take("ai.rever.boss.plugin.dynamic.docker", "docker"))
        // Consumed: the next install of the same id gets whatever that operation
        // knows, not a name left over from a store row the user has moved on from.
        assertEquals("docker", names.take("ai.rever.boss.plugin.dynamic.docker", "docker"))
    }

    @Test
    fun `an unhinted key falls back to what the caller knows`() {
        assertEquals("fallback", DownloadDisplayNames().take("nobody", "fallback"))
    }

    @Test
    fun `a blank hint is not a name`() {
        val names = DownloadDisplayNames()
        names.hint("id", "  ")
        assertEquals("fallback", names.take("id", "fallback"))
    }

    @Test
    fun `the fallback reporter tracks this plugin's own work`() {
        val tracker = DownloadProgressTracker()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val reporter = LocalTransferReporter(tracker, scope)

        // What a host with no download center gets: the bar this plugin has always
        // drawn. No Cancel, because there is no click target to put one on.
        assertTrue(reporter.begin("docker", "Docker", isUpdate = false, onCancel = {}))
        assertFalse(reporter.begin("docker", "Docker", isUpdate = false, onCancel = {}), "nested begin owns nothing")
        reporter.progress("docker", 0.5f)
        assertEquals(0.5f, tracker.downloads.value["docker"]?.progress)
        assertEquals(setOf("docker"), reporter.busyIds.value)

        reporter.end("docker")
        assertTrue(reporter.busyIds.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun `a prompt is also satisfied by a newer version than it offered`() {
        // A toast offering 2.0.0 is just as stale once 2.0.1 is installed from the
        // panel. Demanding equality left it on screen for the whole session.
        assertTrue(promptSatisfied(mapOf("a" to "2.0.0"), mapOf("a" to "2.0.1")))
        assertTrue(promptSatisfied(mapOf("a" to "2.0.0"), mapOf("a" to "2.0.0")))
        assertFalse(promptSatisfied(mapOf("a" to "2.0.0"), mapOf("a" to "1.9.9")))
    }

    @Test
    fun `a button is busy for work this panel did not start`() {
        val state =
            PluginManagerState(
                busyPlugins = setOf("mine"),
                transferringPlugins = setOf("theirs"),
            )

        // The bug this fixes: an update started from the toast, from another
        // window, or from the host's own prompt left this panel's button idle -
        // and pressing it raced the install already running.
        assertEquals(setOf("mine", "theirs"), state.activePlugins)
    }

    @Test
    fun `a prompt is retired only when every plugin it named has landed`() {
        val offered = mapOf("a" to "2.0.0", "b" to "3.0.0")

        assertFalse(
            promptSatisfied(offered, mapOf("a" to "2.0.0", "b" to "2.9.0")),
            "an Update All toast still has something to offer while one is behind",
        )
        assertTrue(promptSatisfied(offered, mapOf("a" to "2.0.0", "b" to "3.0.0")))
    }

    @Test
    fun `a plugin that is not loaded right now has not been updated`() {
        // Every plugin is briefly unloaded during an api hot swap; reading that as
        // "done" would retire a prompt that is still true.
        assertFalse(promptSatisfied(mapOf("a" to "2.0.0"), emptyMap()))
    }

    @Test
    fun `a cancelled download is not reported as a failure`() {
        val cancelled = InstallResult.DownloadFailed(DOWNLOAD_CANCELLED)

        // The user pressed Cancel in the download dialog. Reporting "Install failed"
        // for an answer they gave is the same mistake as reporting a declined
        // dependent-restart prompt as a fault.
        assertNull(outcomeErrorFor(cancelled, PluginAction.INSTALL))
        assertNull(outcomeErrorFor(cancelled, PluginAction.UPDATE))
        // And it must not count towards the Update All banner either.
        assertNull(failureReasonFor(cancelled, PluginAction.UPDATE))
    }

    @Test
    fun `a download that actually failed still says so`() {
        val failed = InstallResult.DownloadFailed("HTTP 503")
        assertEquals("Install failed: HTTP 503", outcomeErrorFor(failed, PluginAction.INSTALL))
    }

    @Test
    fun `an empty prompt satisfies nothing`() {
        // Guards the collector: with no prompt on screen there is nothing to dismiss.
        assertFalse(promptSatisfied(emptyMap(), mapOf("a" to "2.0.0")))
    }
}
