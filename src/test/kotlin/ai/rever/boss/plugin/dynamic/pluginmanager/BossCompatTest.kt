package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app-version floor, and the two ways getting it wrong is worse than not having it.
 *
 * `minBossVersion` is checked by the host's `DynamicPluginLoader`, which refuses the jar outright.
 * The Toolbox had the field on every store row and no way to judge it, so it offered versions this
 * host cannot load and Install failed in the one shape nothing surfaces: a download, a refusal in
 * the host log, and a plugin that never appeared. fluck-browser 1.2.22 (`minBossVersion` 9.4.23) on
 * a 9.4.22 host is the case that prompted this, where the missing plugin IS the browser.
 *
 * Two failure directions matter and each has tests below. Too permissive puts the silent install
 * back. Too strict is worse: this plugin runs on hosts that predate `boss.app.version`, and
 * refusing every install there would break the store on older BOSS to protect a subset of it.
 */
class BossCompatTest {
    private val property = "boss.app.version"

    @AfterTest
    fun clearHostVersion() {
        System.clearProperty(property)
    }

    private fun withHost(
        version: String?,
        body: () -> Unit,
    ) {
        if (version == null) System.clearProperty(property) else System.setProperty(property, version)
        body()
    }

    @Test
    fun `a host at or above the floor is compatible`() {
        withHost("9.4.23") {
            // Equal MUST pass. minBossVersion is a floor, and the release cut to carry a plugin
            // declares the same version that plugin requires - reading this as strictly-greater
            // would refuse every plugin on precisely the host built for it.
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.23"))
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.22"))
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.3.99"))
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("8.0.0"))
        }
    }

    @Test
    fun `a host below the floor needs an update`() {
        withHost("9.4.22") {
            // The exact case that shipped: one patch short.
            assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, BossCompat.status("9.4.23"))
            assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, BossCompat.status("9.5.0"))
            assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, BossCompat.status("10.0.0"))
            assertFalse(BossCompat.isInstallable("9.4.23"))
        }
    }

    @Test
    fun `a newer major host still satisfies an older floor`() {
        // Unlike the IPC contract, where a different major is a hard mismatch, this is a floor:
        // moving forward never breaks it. Copying IpcCompat's MAJOR_MISMATCH rule here would have
        // made every plugin published before BOSS 10 uninstallable on BOSS 10.
        withHost("10.1.0") {
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.23"))
            assertTrue(BossCompat.isInstallable("9.4.23"))
        }
    }

    @Test
    fun `an unknown host version fails open`() {
        // A host older than the property, which is every released BOSS up to 9.4.22. Blocking here
        // would take the store away on those hosts entirely.
        withHost(null) {
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("9.4.23"))
            assertTrue(BossCompat.isInstallable("9.4.23"))
            assertNull(BossCompat.requirement("9.4.23"))
        }
    }

    @Test
    fun `a blank or missing floor fails open`() {
        withHost("9.4.22") {
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status(null))
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status(""))
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("   "))
            assertTrue(BossCompat.isInstallable(null))
            assertTrue(BossCompat.isInstallable(""))
        }
    }

    @Test
    fun `a blank host property is treated as absent`() {
        // System.setProperty("") is reachable: an empty version string published by a build that
        // could not resolve one. Parsing it would throw or, worse, compare as zero and refuse
        // everything.
        withHost("   ") {
            assertNull(BossCompat.hostVersion)
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("9.4.23"))
        }
    }

    @Test
    fun `an unparseable version on either side fails open`() {
        // "9.4" has no patch, and a two-part floor is what several older manifests declare. Guessing
        // a patch of 0 would be a made-up answer either way; UNKNOWN says so.
        withHost("9.4.22") {
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("9.4"))
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("latest"))
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("9.x.0"))
        }
        withHost("dev") {
            assertEquals(BossCompat.Status.UNKNOWN, BossCompat.status("9.4.23"))
        }
    }

    @Test
    fun `qualifiers are ignored on both sides`() {
        // Release candidates and build metadata reach here from local and CI builds. A qualifier
        // must not decide compatibility, and must not make the whole comparison unparseable.
        withHost("9.4.23-rc1") {
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.23"))
        }
        withHost("9.4.23+build.7") {
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.23"))
        }
        withHost("9.4.22") {
            assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, BossCompat.status("9.4.23-rc1"))
        }
    }

    @Test
    fun `the requirement names both versions`() {
        // "Needs a newer BOSS" leaves the reader unable to tell whether updating would help. This
        // string is what the store card and the version sheet show, so it has to be actionable.
        withHost("9.4.22") {
            val message = BossCompat.requirement("9.4.23")
            assertEquals("Needs BOSS 9.4.23 (you have 9.4.22)", message)
        }
    }

    @Test
    fun `the requirement is silent when nothing is wrong`() {
        withHost("9.4.23") {
            assertNull(BossCompat.requirement("9.4.23"))
            assertNull(BossCompat.requirement(""))
        }
    }

    @Test
    fun `multi digit segments compare numerically`() {
        // String comparison would put "9.4.9" above "9.4.10", which is the version range this
        // plugin actually lives in.
        withHost("9.4.10") {
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.9"))
        }
        withHost("9.4.9") {
            assertEquals(BossCompat.Status.REQUIRES_HOST_UPDATE, BossCompat.status("9.4.10"))
        }
    }

    @Test
    fun `extra segments beyond patch do not break parsing`() {
        // A four-part version is not hypothetical: some plugin manifests carry a build number.
        withHost("9.4.23.1") {
            assertEquals(BossCompat.Status.COMPATIBLE, BossCompat.status("9.4.23"))
        }
    }
}
