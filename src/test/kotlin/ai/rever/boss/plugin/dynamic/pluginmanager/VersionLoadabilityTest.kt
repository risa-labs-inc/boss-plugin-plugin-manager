package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.IpcCompat
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

    private fun version(
        v: String = "1.2.22",
        minIpc: String = "1.0.0",
        minBoss: String = "",
    ) = PluginVersionInfo(
        version = v,
        minIpcVersion = minIpc,
        compatibility = IpcCompat.status(minIpc),
        minBossVersion = minBoss,
        bossCompatibility = BossCompat.status(minBoss),
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
        // `bossCompatibility` defaults to UNKNOWN, so an entry built by a caller that does not set
        // it (or decoded from a store that does not send the column) is never blocked by accident.
        System.setProperty(property, "9.4.22")
        assertTrue(PluginVersionInfo(version = "1.0.0", minIpcVersion = "1.0.0", compatibility = IpcCompat.Status.UNKNOWN).isLoadableHere())
    }
}
