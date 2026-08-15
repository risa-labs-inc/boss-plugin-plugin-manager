package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Update button tells the user, per outcome.
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
            updateErrorFor(
                InstallResult.LoadFailed(
                    "could not replace the installed version - the host refused to unload it",
                ),
            )

        assertNotNull(error, "a LoadFailed update must say something; silence reads as a dead button")
        assertTrue(error.contains("refused to unload it"), "the reason must survive: was <$error>")
    }

    @Test
    fun `a download failure is reported`() {
        val error = updateErrorFor(InstallResult.DownloadFailed("HTTP 503"))

        assertEquals("Update failed: HTTP 503", error)
    }

    @Test
    fun `a version conflict names both versions`() {
        val error = updateErrorFor(InstallResult.VersionConflict(required = "9.2.60", available = "9.2.55"))

        assertNotNull(error)
        assertTrue(error.contains("9.2.60") && error.contains("9.2.55"), "was <$error>")
    }

    @Test
    fun `success says nothing`() {
        assertNull(updateErrorFor(InstallResult.Success(pluginInfo())))
    }

    @Test
    fun `already installed is not an error`() {
        // The row has nothing left to offer; saying "Update failed" for it would be wrong.
        assertNull(updateErrorFor(InstallResult.AlreadyInstalled("1.1.1")))
    }

    @Test
    fun `every failure variant produces a message`() {
        // The guard against this bug recurring. `updateErrorFor` is exhaustive with no `else`,
        // so a new InstallResult variant is a compile error there - this pins the other half,
        // that no variant which represents a failure is allowed to resolve to null.
        val failures =
            listOf(
                InstallResult.DownloadFailed("x"),
                InstallResult.LoadFailed("x"),
                InstallResult.VersionConflict(required = "2.0.0", available = "1.0.0"),
            )

        failures.forEach { result ->
            assertNotNull(updateErrorFor(result), "${result::class.simpleName} resolved to no message")
        }
    }
}
