package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a failed version-sheet install is reported.
 *
 * The sheet is a dialog over the panel and stays open on failure, so a message written to the
 * panel banner was painted underneath the dialog that produced it: the user pressed Install and
 * saw nothing change.
 */
class VersionSheetErrorTest {
    private fun sheet(pluginId: String) =
        VersionSheetState(
            pluginId = pluginId,
            displayName = pluginId,
            installedVersion = "1.2.0",
            isLoading = false,
            hostIpcVersion = "1.0.0",
        )

    @Test
    fun `a failure lands in the sheet that produced it, not behind it`() {
        val state = PluginManagerState(versionSheet = sheet("flow"))

        val after = state.withVersionInstallError("flow", "Version change failed: HTTP 503")

        assertEquals("Version change failed: HTTP 503", after.versionSheet?.installError)
        assertNull(after.error, "the panel banner sits under the dialog, where nobody can read it")
        // The list slot is untouched: the sheet stays usable for picking another version.
        assertNull(after.versionSheet?.error)
    }

    @Test
    fun `with no sheet for that plugin, the banner reports it`() {
        assertEquals(
            "Install failed: HTTP 503",
            PluginManagerState().withVersionInstallError("flow", "Install failed: HTTP 503").error,
        )

        // A sheet for a DIFFERENT plugin must not carry this plugin's failure.
        val other = PluginManagerState(versionSheet = sheet("docker")).withVersionInstallError("flow", "Install failed: x")
        assertEquals("Install failed: x", other.error)
        assertNull(other.versionSheet?.installError)
    }

    @Test
    fun `starting another attempt clears the previous failure`() {
        val failed = PluginManagerState(versionSheet = sheet("flow")).withVersionInstallError("flow", "Install failed: x")

        assertNull(failed.withVersionInstallError("flow", null).versionSheet?.installError)
    }
}
