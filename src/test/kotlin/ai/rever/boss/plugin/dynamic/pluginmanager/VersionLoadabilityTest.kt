package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.IpcCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginVersionInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.blockedReason
import ai.rever.boss.plugin.dynamic.pluginmanager.api.isLoadableHere
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The predicate behind the version sheet's Install button.
 *
 * The sheet decided from the IPC status alone, so every version above the app floor rendered as
 * installable. Both floors have to pass, and each fails open independently - covered here because
 * the row that reads this is a private composable with no test harness in this plugin, and a
 * predicate inlined into a composable is a predicate nobody can check.
 */
class VersionLoadabilityTest {
    private val property = "boss.app.version"
    private val ipcProperty = "boss.ipc.version"

    @AfterTest
    fun clearProperties() {
        System.clearProperty(property)
        System.clearProperty(ipcProperty)
    }

    /**
     * @param compatibility defaulted from [minIpc] but overridable, because the two are NOT built
     *   from the same input in production: `min_ipc_version` is nullable, and the entry coerces the
     *   string to "1.0.0" while resolving the status from the raw null. Deriving both from one
     *   argument here made them equal by construction and hid a real divergence.
     */
    private fun version(
        v: String = "1.2.22",
        minIpc: String = "1.0.0",
        minBoss: String = "",
        compatibility: IpcCompat.Status = IpcCompat.status(minIpc),
    ) = PluginVersionInfo(
        version = v,
        minIpcVersion = minIpc,
        compatibility = compatibility,
        minBossVersion = minBoss,
    )

    @Test
    fun `a version above the app floor is not loadable`() {
        System.setProperty(property, "9.4.22")
        val v = version(minBoss = "9.4.23")
        assertFalse(v.isLoadableHere())
        assertEquals("Needs BOSS 9.4.23 (you have 9.4.22)", v.blockedReason())
    }

    @Test
    fun `the same version is loadable on a host that meets the floor`() {
        System.setProperty(property, "9.4.23")
        val v = version(minBoss = "9.4.23")
        assertTrue(v.isLoadableHere())
        assertNull(v.blockedReason())
    }

    @Test
    fun `the IPC floor still blocks on its own`() {
        // The app floor must not have replaced the check that was already there.
        System.setProperty(property, "9.9.9")
        System.setProperty(ipcProperty, "1.2.0")
        val v = version(minIpc = "1.3.0", minBoss = "9.4.0")
        assertFalse(v.isLoadableHere())
        assertEquals("Needs newer BOSS", v.blockedReason())
    }

    @Test
    fun `both floors clear means loadable`() {
        System.setProperty(property, "9.9.9")
        System.setProperty(ipcProperty, "1.3.0")
        assertTrue(version(minIpc = "1.3.0", minBoss = "9.4.0").isLoadableHere())
    }

    @Test
    fun `the app floor message wins over the IPC one`() {
        // When both block, name the one a user can act on. "host IPC 1.3.0" is not a thing anyone
        // can go and install.
        System.setProperty(property, "9.4.22")
        System.setProperty(ipcProperty, "1.2.0")
        val v = version(minIpc = "1.3.0", minBoss = "9.4.23")
        assertEquals("Needs BOSS 9.4.23 (you have 9.4.22)", v.blockedReason())
    }

    @Test
    fun `an unknown host fails open on both floors`() {
        // A host publishing neither property, which is every BOSS before these were added. The
        // version sheet must still offer installs there.
        System.clearProperty(property)
        System.clearProperty(ipcProperty)
        val v = version(minIpc = "9.9.9", minBoss = "99.0.0")
        assertTrue(v.isLoadableHere())
        assertNull(v.blockedReason())
    }

    @Test
    fun `a default-constructed entry is loadable`() {
        // `minBossVersion` defaults to blank, which resolves to UNKNOWN, so an entry built by a
        // caller that does not set it is never blocked by accident.
        System.setProperty(property, "9.4.22")
        val bare =
            PluginVersionInfo(
                version = "1.0.0",
                minIpcVersion = "1.0.0",
                compatibility = IpcCompat.Status.UNKNOWN,
            )
        assertTrue(bare.isLoadableHere())
        assertNull(bare.blockedReason())
    }

    @Test
    fun `the verdict is derived, so it cannot disagree with the floor`() {
        // The two used to be independently defaulted constructor parameters, and the consumers
        // disagreed about which was authoritative: setting the floor and forgetting the verdict
        // (easy, it was defaulted) rendered a blocked version as installable.
        System.setProperty(property, "9.4.22")
        val blocked = version(minBoss = "9.4.23")
        assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, blocked.bossCompatibility)
        assertFalse(blocked.isLoadableHere())
    }

    @Test
    fun `a resolved UNKNOWN status is not overridden by the coerced string`() {
        // The divergence this predicate had. `min_ipc_version` null resolves to UNKNOWN and the
        // badge shows nothing, while the coerced "1.0.0" string would read as MAJOR_MISMATCH on any
        // host whose IPC major is not 1 - a row with no badge and no action, for a version that
        // declares no IPC floor at all.
        System.setProperty(ipcProperty, "2.0.0")
        val noIpcFloor = version(minIpc = "1.0.0", compatibility = IpcCompat.Status.UNKNOWN)
        assertTrue(
            noIpcFloor.isLoadableHere(),
            "a version declaring no IPC floor was blocked by a coerced default the badge never showed",
        )
    }

    @Test
    fun `a hard IPC mismatch still blocks`() {
        // The other side of the same fix: reading the resolved status must not have made the
        // predicate more permissive than the badge.
        System.setProperty(ipcProperty, "2.0.0")
        val mismatched = version(minIpc = "1.0.0", compatibility = IpcCompat.Status.MAJOR_MISMATCH)
        assertFalse(mismatched.isLoadableHere())
        assertEquals("Needs newer BOSS", mismatched.blockedReason())
    }
}

/**
 * The store card's predicate, which judges the LATEST version only.
 *
 * Separate from the version sheet's because the two see different data: a catalogue row carries
 * `latest_min_boss_version` and no IPC floor at all, so the card can answer one question and the
 * sheet answers both. This was the one of the four call sites with no behavioural test behind it.
 */
class StoreItemBlockedReasonTest {
    private val property = "boss.app.version"

    @AfterTest
    fun clearHostVersion() {
        System.clearProperty(property)
    }

    @Test
    fun `a latest version above the floor is blocked and names both versions`() {
        System.setProperty(property, "9.4.22")
        val item = PluginStoreItem(pluginId = "com.example.plugin", minBossVersion = "9.4.23")
        assertEquals("Needs BOSS 9.4.23 (you have 9.4.22)", item.blockedReason())
    }

    @Test
    fun `a met floor is not blocked`() {
        System.setProperty(property, "9.4.23")
        assertNull(PluginStoreItem(pluginId = "com.example.plugin", minBossVersion = "9.4.23").blockedReason())
    }

    @Test
    fun `a row with no floor is not blocked`() {
        // The default for `PluginStoreItem.minBossVersion` is blank, and most catalogue rows are
        // blank or "1.0.0". Blocking on absence would empty the store.
        System.setProperty(property, "9.4.22")
        assertNull(PluginStoreItem(pluginId = "com.example.plugin").blockedReason())
        assertNull(PluginStoreItem(pluginId = "com.example.plugin", minBossVersion = "1.0.0").blockedReason())
    }

    @Test
    fun `an older host that publishes no version is not blocked`() {
        System.clearProperty(property)
        assertNull(PluginStoreItem(pluginId = "com.example.plugin", minBossVersion = "99.0.0").blockedReason())
    }
}
