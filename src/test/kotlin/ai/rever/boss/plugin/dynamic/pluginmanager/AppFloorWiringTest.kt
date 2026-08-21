package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the app-floor check is actually wired into the four places that install a plugin.
 *
 * A **source** test, which proves less than a behavioural one and is here only for the call sites
 * that cannot be reached from a unit test: two of them are private composables (no Compose test
 * harness in this plugin) and two are inside suspend functions that talk to Postgrest. The
 * decisions themselves are tested properly in [BossCompatTest], [VersionLoadabilityTest] and
 * [LoadableUpdatesTest]; what is checked here is only that those decisions are consulted.
 *
 * It earns its brittleness from what the regression cost: every one of these call sites offered a
 * version this host cannot load, and the failure is silent - `DynamicPluginLoader` refuses the jar
 * and writes one line to the host log. Nothing in this plugin's UI ever said why.
 */
class AppFloorWiringTest {
    private fun source(relative: String): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "src/main/kotlin").isDirectory }
        val file = File(assertNotNull(root, "could not locate the module root"), relative)
        assertTrue(file.isFile, "missing source file: $relative")
        return file.readText()
    }

    private val view by lazy {
        source("src/main/kotlin/ai/rever/boss/plugin/dynamic/pluginmanager/PluginManagerView.kt")
    }

    private val impl by lazy {
        source("src/main/kotlin/ai/rever/boss/plugin/dynamic/pluginmanager/impl/PluginManagerAPIImpl.kt")
    }

    @Test
    fun `the store card consults the floor before offering Install`() {
        assertTrue(
            view.contains("val bossFloorLabel = plugin.blockedReason()"),
            "AvailablePluginCard no longer resolves the app floor",
        )
        assertTrue(
            view.contains("bossFloorLabel != null -> {"),
            "the card computes the floor but has no branch acting on it, so Install is still offered",
        )
    }

    @Test
    fun `the card's floor branch sits above the Install fallback`() {
        // Ordering is the whole point of a `when`: below the `else ->` that renders Install, the
        // branch is dead code and the bug is back with a test still passing.
        val block = view.substringAfter("val bossFloorLabel = plugin.blockedReason()")
        val branch = block.indexOf("bossFloorLabel != null -> {")
        val install = block.indexOf("""text = "Install",""")
        assertTrue(branch in 0 until install, "the floor branch is not reached before Install is rendered")
    }

    @Test
    fun `the version row consults both floors`() {
        assertTrue(
            view.contains("val blockedLabel = version.blockedReason()"),
            "VersionRow no longer resolves the blocked reason",
        )
        assertTrue(
            view.contains("blockedLabel != null -> Text(blockedLabel"),
            "VersionRow computes a reason it does not act on",
        )
    }

    @Test
    fun `the compatibility badge reflects the app floor`() {
        // A green "Compatible" chip beside a blocked row is the exact wording the user objected to.
        assertTrue(
            view.contains("if (version.bossCompatibility == BossCompat.Status.REQUIRES_HOST_UPDATE)"),
            "the badge is back to reading the IPC status alone",
        )
    }

    @Test
    fun `both download paths gate on the floor`() {
        // The last line of defence, and the one that covers a deep link or a stale list reaching
        // install without passing any UI filter. Two call sites: fresh install and update.
        val gates = Regex("""BossCompat\.requirement\(downloadInfo\.minBossVersion\)""").findAll(impl).count()
        assertTrue(gates == 2, "expected the floor gate on both download paths, found $gates")
    }

    @Test
    fun `the update check gates on the floor`() {
        assertTrue(
            impl.contains("loadableUpdates(rows, installedVersionMap)"),
            "checkForUpdatesResult no longer filters candidates through loadableUpdates",
        )
        assertTrue(
            impl.contains("BossCompat.isInstallable(versionRow?.minBossVersion)"),
            "checkForCompatibleUpdates no longer applies the app floor",
        )
    }

    @Test
    fun `the update query still asks for the floor column`() {
        // Without the column the row's floor is null, every check resolves to UNKNOWN, and the
        // filter passes everything while looking like it works.
        assertTrue(
            impl.contains("plugin_id, latest_version, latest_min_boss_version"),
            "the update query dropped latest_min_boss_version, so the filter has nothing to read",
        )
    }
}
