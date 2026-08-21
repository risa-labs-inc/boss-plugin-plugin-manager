package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginUpdateRow
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.loadableUpdates
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The update list the Toolbox panel shows.
 *
 * This is where the bug lived. `checkForCompatibleUpdates` (which drives the prompt) filtered on the
 * IPC floor, but the panel's own list comes from `checkForUpdatesResult`, which filtered on nothing
 * except "is it newer" - so an Update button appeared for a version whatever it required of the
 * host. Pressing it downloaded over the installed jar and the loader then refused the result, which
 * does not fail safe: the working plugin is already gone.
 */
class LoadableUpdatesTest {
    private val property = "boss.app.version"

    @AfterTest
    fun clearHostVersion() {
        System.clearProperty(property)
    }

    private fun row(
        id: String,
        latest: String?,
        floor: String? = null,
    ) = PluginUpdateRow(pluginId = id, latestVersion = latest, latestMinBossVersion = floor)

    @Test
    fun `an update above the host floor is not offered`() {
        System.setProperty(property, "9.4.22")
        val updates =
            loadableUpdates(
                rows = listOf(row("fluck-browser", "1.2.22", floor = "9.4.23")),
                installedVersions = mapOf("fluck-browser" to "1.2.21"),
            )
        assertTrue(
            updates.isEmpty(),
            "offered an update the loader would refuse, replacing a working plugin with nothing",
        )
    }

    @Test
    fun `the same update is offered once the host meets the floor`() {
        System.setProperty(property, "9.4.23")
        val updates =
            loadableUpdates(
                rows = listOf(row("fluck-browser", "1.2.22", floor = "9.4.23")),
                installedVersions = mapOf("fluck-browser" to "1.2.21"),
            )
        assertEquals(mapOf("fluck-browser" to "1.2.22"), updates)
    }

    @Test
    fun `a blank floor is still offered`() {
        // Most published versions declare no floor at all. Treating an absent floor as a blocker
        // would empty the Updates tab for nearly everything.
        System.setProperty(property, "9.4.22")
        val updates =
            loadableUpdates(
                rows = listOf(row("a", "2.0.0", floor = ""), row("b", "2.0.0", floor = null)),
                installedVersions = mapOf("a" to "1.0.0", "b" to "1.0.0"),
            )
        assertEquals(mapOf("a" to "2.0.0", "b" to "2.0.0"), updates)
    }

    @Test
    fun `an older host that publishes no version still gets its updates`() {
        // Every BOSS up to 9.4.22 is this case. The filter must not become a blanket refusal there.
        System.clearProperty(property)
        val updates =
            loadableUpdates(
                rows = listOf(row("fluck-browser", "1.2.22", floor = "9.4.23")),
                installedVersions = mapOf("fluck-browser" to "1.2.21"),
            )
        assertEquals(mapOf("fluck-browser" to "1.2.22"), updates)
    }

    @Test
    fun `one blocked plugin does not hide the others`() {
        // The filter is per row. An early return over the whole list would have made a single
        // incompatible plugin silence every other update the user has waiting.
        System.setProperty(property, "9.4.22")
        val updates =
            loadableUpdates(
                rows =
                    listOf(
                        row("blocked", "2.0.0", floor = "9.9.0"),
                        row("fine", "2.0.0", floor = "9.4.0"),
                    ),
                installedVersions = mapOf("blocked" to "1.0.0", "fine" to "1.0.0"),
            )
        assertEquals(mapOf("fine" to "2.0.0"), updates)
    }

    @Test
    fun `a version that is not newer is not offered whatever its floor`() {
        // The original condition still has to hold: this filter narrows the list, it does not
        // replace the newness check.
        System.setProperty(property, "9.9.9")
        val updates =
            loadableUpdates(
                rows = listOf(row("same", "1.0.0", floor = "9.4.0"), row("older", "0.9.0")),
                installedVersions = mapOf("same" to "1.0.0", "older" to "1.0.0"),
            )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a plugin that is not installed is not an update`() {
        System.setProperty(property, "9.9.9")
        val updates = loadableUpdates(listOf(row("ghost", "1.0.0")), emptyMap())
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `a row with no latest version is skipped`() {
        System.setProperty(property, "9.9.9")
        val updates = loadableUpdates(listOf(row("a", null)), mapOf("a" to "1.0.0"))
        assertTrue(updates.isEmpty())
    }
}
