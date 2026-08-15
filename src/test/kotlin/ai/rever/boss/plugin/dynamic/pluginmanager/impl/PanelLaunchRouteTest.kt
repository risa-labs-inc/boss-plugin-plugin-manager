package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which route the Open button takes for a panel plugin, and how it decides the best one is
 * available.
 *
 * Worth pinning because every wrong answer here still renders an ordinary button and still
 * opens *something*. Falling to the reflective tier on a host that supports the real call
 * would silently keep resetting each panel's state - which is the whole reason
 * `openPanelAsTab` was added (BossConsole#177).
 *
 * The probe cases use stand-in interfaces rather than a fake `SplitViewOperations`, for the
 * same reason [RegisteredSurface] is a flattened copy: implementing the real thing would drag
 * the api jar into this source set, and CI resolves the LATEST api release, so an abstract
 * member added to an interface this repo does not own would turn an unrelated PR red. It also
 * buys the case that matters most - a host whose interface has NO such member cannot be built
 * out of the real one, because the real one always has it.
 */
class PanelLaunchRouteTest {
    // ---- the ordering ----

    @Test
    fun `the sanctioned call wins wherever the host has it`() {
        assertEquals(
            PanelLaunchRoute.OPEN_PANEL_AS_TAB,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = true,
                // Both lower tiers available: the point is that neither is preferred.
                canBuildPanelHostTab = true,
                canRevealInSidebar = true,
            ),
        )
    }

    @Test
    fun `an older host still reaches the main view through the reflective bridge`() {
        assertEquals(
            PanelLaunchRoute.PANEL_HOST_TAB,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = false,
                canBuildPanelHostTab = true,
                canRevealInSidebar = true,
            ),
        )
    }

    @Test
    fun `a host that can do neither still opens the tool, in the sidebar`() {
        assertEquals(
            PanelLaunchRoute.SIDEBAR_REVEAL,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = false,
                canBuildPanelHostTab = false,
                canRevealInSidebar = true,
            ),
        )
    }

    @Test
    fun `nothing to open falls through to the caller's homepage handling`() {
        assertEquals(
            PanelLaunchRoute.NONE,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = false,
                canBuildPanelHostTab = false,
                canRevealInSidebar = false,
            ),
        )
    }

    /**
     * The capability outranks the sidebar even where the reflective bridge is unavailable -
     * a host with the real call has no reason to be demoted for a bridge it does not need.
     */
    @Test
    fun `the sanctioned call does not need the bridge to be available`() {
        assertEquals(
            PanelLaunchRoute.OPEN_PANEL_AS_TAB,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = true,
                canBuildPanelHostTab = false,
                canRevealInSidebar = false,
            ),
        )
    }

    /**
     * A promote that throws re-enters the same function with the capability struck off, rather
     * than choosing its own successor inline. This is that second pass: the bridge, not the
     * sidebar, which is what keeps the degradation order equal to the initial order.
     */
    @Test
    fun `striking the capability off drops to the bridge, not past it`() {
        assertEquals(
            PanelLaunchRoute.PANEL_HOST_TAB,
            panelLaunchRoute(
                hostSupportsOpenPanelAsTab = false,
                canBuildPanelHostTab = true,
                canRevealInSidebar = true,
            ),
        )
    }

    // ---- the capability probe ----

    /** A host on boss-plugin-api 1.0.77+ whose provider implements the call. */
    private interface NewOps {
        val supportsOpenPanelAsTab: Boolean

        fun openPanelAsTab(panelId: String)
    }

    /** A host pinned below it: the interface has neither member at all. */
    private interface OldOps {
        fun openTab(tabInfo: String)
    }

    /** The drift the probe guards against: the flag is there, the call's signature moved. */
    private interface DriftedOps {
        val supportsOpenPanelAsTab: Boolean

        fun openPanelAsTab(panelId: String, windowId: String)
    }

    private fun newOps(supports: Boolean) =
        object : NewOps {
            override val supportsOpenPanelAsTab: Boolean = supports

            override fun openPanelAsTab(panelId: String) = Unit
        }

    @Test
    fun `a host that implements it is detected`() {
        assertTrue(supportsOpenPanelAsTab(newOps(supports = true), NewOps::class.java))
    }

    @Test
    fun `a host that only inherits the default no-op is not`() {
        // A host pinned to an api that HAS the member but whose provider never overrode it.
        assertFalse(supportsOpenPanelAsTab(newOps(supports = false), NewOps::class.java))
    }

    /**
     * The case the probe exists for, and the reason reading `ops.supportsOpenPanelAsTab`
     * directly would be the very crash it prevents: on this host the member does not exist.
     */
    @Test
    fun `a host whose interface has no such member is unsupported, not a crash`() {
        val old = object : OldOps {
            override fun openTab(tabInfo: String) = Unit
        }

        assertFalse(supportsOpenPanelAsTab(old, OldOps::class.java))
    }

    /**
     * The flag and the call are two questions and only the second is what the call site binds
     * to. A flag-only check would answer yes here and then throw NoSuchMethodError.
     */
    @Test
    fun `a flag whose call has drifted is unsupported`() {
        val drifted = object : DriftedOps {
            override val supportsOpenPanelAsTab: Boolean = true

            override fun openPanelAsTab(panelId: String, windowId: String) = Unit
        }

        assertFalse(supportsOpenPanelAsTab(drifted, DriftedOps::class.java))
    }

    @Test
    fun `a throwing implementation is treated as unsupported, never propagated`() {
        val broken = object : NewOps {
            override val supportsOpenPanelAsTab: Boolean get() = error("this host's provider is broken")

            override fun openPanelAsTab(panelId: String) = Unit
        }

        assertFalse(supportsOpenPanelAsTab(broken, NewOps::class.java))
    }

    /** An object that is not an instance of the interface at all cannot be coaxed into `true`. */
    @Test
    fun `a mismatched provider is unsupported`() {
        assertFalse(supportsOpenPanelAsTab("not a provider", NewOps::class.java))
    }
}
