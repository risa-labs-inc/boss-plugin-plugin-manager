package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
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
    private fun pluginInfo() =
        PluginInfo(
            pluginId = "ai.rever.boss.plugin.dynamic.aigateway",
            displayName = "AI Gateway",
            version = "1.1.1",
            description = "",
            author = "Risa Labs",
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
    fun `already installed is benign for install and a failure for update`() {
        // Install checks before doing any work, so there was simply nothing to do. The update
        // path only reaches that check AFTER its uninstall reported success, so the same value
        // means the old version survived and the update silently did not happen.
        assertNull(outcomeErrorFor(InstallResult.AlreadyInstalled("1.1.1"), PluginAction.INSTALL))

        val update = outcomeErrorFor(InstallResult.AlreadyInstalled("1.1.0"), PluginAction.UPDATE)
        assertNotNull(update, "an update that left the old version installed must not read as success")
        assertTrue(update.contains("1.1.0"), "the stranded version should be named: was <$update>")
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
        // updateAllPlugins uses `outcomeErrorFor(...) != null` as its failure predicate, so the
        // label is the only thing separating an install message from an update one.
        assertTrue(outcomeErrorFor(InstallResult.LoadFailed("x"), PluginAction.UPDATE)!!.startsWith("Update failed"))
        assertTrue(outcomeErrorFor(InstallResult.LoadFailed("x"), PluginAction.INSTALL)!!.startsWith("Install failed"))
    }
}
