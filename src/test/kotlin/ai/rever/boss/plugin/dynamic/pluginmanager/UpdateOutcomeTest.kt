package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Install and Update buttons tell the user, per outcome.
 *
 * Tested as a pure function rather than through the ViewModel because the failure mode was
 * silence: a refused update cleared the spinner, set no error and left the row still offering
 * the update, which is indistinguishable from a button that is not wired up. Nothing about the
 * rendered UI was wrong, so only the decision itself is worth pinning.
 *
 * The case that produced it: the AI Gateway could not be unloaded while jupyter-notebook,
 * flow-tab or llmrpa was loaded, and the Toolbox updates a plugin by uninstalling and
 * reinstalling it - so the host's refusal arrived here as [InstallResult.LoadFailed] and was
 * dropped.
 */
class UpdateOutcomeTest {
    private fun pluginInfo() = PluginInfo("ai.rever.boss.plugin.dynamic.aigateway", "AI Gateway", "1.1.1")

    private fun update(pluginId: String, displayName: String = pluginId) =
        UpdateInfo(
            pluginId = pluginId,
            displayName = displayName,
            currentVersion = "1.0.0",
            newVersion = "1.1.0",
        )

    @Test
    fun `a refused unload is reported, not swallowed`() {
        val error =
            outcomeErrorFor(
                InstallResult.LoadFailed(
                    "could not replace the installed version - the host refused to unload it",
                ),
                PluginAction.UPDATE,
            )

        assertNotNull(error, "a LoadFailed update must say something; silence reads as a dead button")
        assertTrue(error.contains("refused to unload it"), "the reason must survive: was <$error>")
    }

    @Test
    fun `a download failure is reported`() {
        assertEquals("Update failed: HTTP 503", outcomeErrorFor(InstallResult.DownloadFailed("HTTP 503"), PluginAction.UPDATE))
        assertEquals("Install failed: HTTP 503", outcomeErrorFor(InstallResult.DownloadFailed("HTTP 503"), PluginAction.INSTALL))
    }

    @Test
    fun `a version conflict names both versions`() {
        // Reachable from the version sheet, where it used to fall into an `else` and say nothing.
        PluginAction.entries.forEach { action ->
            val error = outcomeErrorFor(InstallResult.VersionConflict(required = "9.2.60", available = "9.2.55"), action)

            assertNotNull(error, "$action said nothing about a version conflict")
            assertTrue(error.contains("9.2.60") && error.contains("9.2.55"), "was <$error>")
        }
    }

    @Test
    fun `success says nothing`() {
        PluginAction.entries.forEach { action ->
            assertNull(outcomeErrorFor(InstallResult.Success(pluginInfo()), action))
        }
    }

    @Test
    fun `already installed is not a failure for install, but is still reported`() {
        // Install checks before doing any work, so nothing failed and it must not be counted as
        // a failure by Update All's tally. It is still worth saying: the user pressed a button
        // and nothing happened, which is the shape of bug this change exists to stop.
        assertNull(failureReasonFor(InstallResult.AlreadyInstalled("1.1.1"), PluginAction.INSTALL))

        val install = outcomeErrorFor(InstallResult.AlreadyInstalled("1.1.1"), PluginAction.INSTALL)
        assertEquals("Plugin already installed (v1.1.1)", install)
    }

    @Test
    fun `already installed is a failure for update`() {
        // The update path only reaches that check AFTER its uninstall reported success, so the
        // same value means the old version survived and the update silently did not happen.
        val reason = failureReasonFor(InstallResult.AlreadyInstalled("1.1.0"), PluginAction.UPDATE)
        assertNotNull(reason, "an update that left the old version installed must not read as success")
        assertTrue(reason.contains("1.1.0"), "the stranded version should be named: was <$reason>")
    }

    @Test
    fun `every failure variant produces a message for every action`() {
        // The guard against this bug recurring. `outcomeErrorFor` is exhaustive with no `else`,
        // so a new InstallResult variant is a compile error there - this pins the other half,
        // that no variant representing a failure is allowed to resolve to null.
        val failures =
            listOf(
                InstallResult.DownloadFailed("x"),
                InstallResult.LoadFailed("x"),
                InstallResult.VersionConflict(required = "2.0.0", available = "1.0.0"),
            )

        PluginAction.entries.forEach { action ->
            failures.forEach { result ->
                assertNotNull(
                    outcomeErrorFor(result, action),
                    "${result::class.simpleName} resolved to no message for $action",
                )
            }
        }
    }

    @Test
    fun `the action names itself in the message`() {
        assertTrue(outcomeErrorFor(InstallResult.LoadFailed("x"), PluginAction.UPDATE)!!.startsWith("Update failed"))
        assertTrue(outcomeErrorFor(InstallResult.LoadFailed("x"), PluginAction.INSTALL)!!.startsWith("Install failed"))
    }

    @Test
    fun `a reason carries no verb, so composing it does not stack failures`() {
        // The whole point of the split: Update All puts the cause inside its own sentence, and
        // "Failed to update X: Update failed: ..." would be the string this change is retiring.
        val reason = failureReasonFor(InstallResult.LoadFailed("the host refused to unload it"), PluginAction.UPDATE)

        assertEquals("the host refused to unload it", reason)
    }

    @Test
    fun `update all names the plugin and the reason, not just the plugin`() {
        assertNull(updateAllError(emptyList()))

        val one = updateAllError(listOf("AI Gateway" to "the host refused to unload it"))
        assertNotNull(one)
        assertTrue(one.contains("AI Gateway"), "was <$one>")
        // The regression this fixes: the old banner named the plugin and never the cause.
        assertTrue(one.contains("the host refused to unload it"), "was <$one>")

        val many = updateAllError(listOf("AI Gateway" to "refused", "Flow" to "HTTP 500"))
        assertNotNull(many)
        listOf("AI Gateway", "Flow", "refused", "HTTP 500").forEach {
            assertTrue(many.contains(it), "<$it> missing from <$many>")
        }
    }

    @Test
    fun `update all keeps rows that did not succeed, including ones that arrived mid-run`() {
        val current = listOf(update("a"), update("b"), update("arrived.during.the.run"))

        // "a" updated; "b" failed. The third row was written by the background poller while the
        // loop was suspended, so it is in neither tally - dropping it, as filtering by the
        // failed set would, silently loses an update the user never got to act on.
        assertEquals(
            listOf("b", "arrived.during.the.run"),
            remainingUpdates(current, succeeded = setOf("a")).map { it.pluginId },
        )
    }

    @Test
    fun `update all clears the list when everything succeeds`() {
        val current = listOf(update("a"), update("b"))

        assertEquals(emptyList(), remainingUpdates(current, succeeded = setOf("a", "b")))
    }
}
