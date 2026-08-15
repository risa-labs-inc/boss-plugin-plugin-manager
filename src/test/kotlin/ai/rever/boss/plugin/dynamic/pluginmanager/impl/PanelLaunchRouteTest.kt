package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabsComponent
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which route the Open button takes for a panel plugin, and how it decides the best one is
 * available.
 *
 * Worth pinning because every wrong answer here still renders an ordinary button and still
 * opens *something*. Falling to the reflective tier on a host that supports the real call
 * would silently keep resetting each panel's state - which is the whole reason
 * `openPanelAsTab` was added (BossConsole#177).
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

    // ---- the capability probe ----

    @Test
    fun `a host that implements it is detected`() {
        assertTrue(supportsOpenPanelAsTab(FakeSplitViewOperations(supports = true)))
    }

    @Test
    fun `a host that only inherits the default no-op is not`() {
        // Exactly the shape of a host pinned to an api that HAS the member but whose
        // SplitViewOperations implementation never overrode it: the defaulted false.
        assertFalse(supportsOpenPanelAsTab(FakeSplitViewOperations(supports = false)))
    }

    /**
     * The case the probe exists for - a host whose pinned api has neither member, where
     * reading the flag directly is a NoSuchMethodError - cannot be built in this JVM: the
     * `SplitViewOperations` on the test classpath is the 1.0.77 one, so `getMethod` always
     * finds it here. What is pinned instead is that the probe never propagates a failure,
     * which is what makes the missing-method case degrade rather than crash.
     */
    @Test
    fun `a throwing implementation is treated as unsupported, never propagated`() {
        assertFalse(supportsOpenPanelAsTab(ThrowingSplitViewOperations))
    }

    private open class FakeSplitViewOperations(
        private val supports: Boolean,
    ) : SplitViewOperations {
        var promoted: PanelId? = null
            private set
        var openedTab: TabInfo? = null
            private set

        override val supportsOpenPanelAsTab: Boolean get() = supports

        override fun openPanelAsTab(panelId: PanelId) {
            promoted = panelId
        }

        override fun openTab(tabInfo: TabInfo) {
            openedTab = tabInfo
        }

        override fun openUrlInActivePanel(url: String, title: String, forceNewTab: Boolean) = Unit

        override fun openFileInActivePanel(filePath: String, fileName: String) = Unit

        override fun openFileInEditor(filePath: String, fileName: String) = Unit

        override fun openFileAtPosition(filePath: String, fileName: String, line: Int, column: Int) = Unit

        override fun setActivePanel(panelId: String) = Unit

        override fun preserveCurrentState(workspaceId: String, workspaceName: String) = Unit

        override fun getActiveTabsComponent(): TabsComponent? = null

        override fun applyWorkspace(workspace: LayoutWorkspace) = Unit

        override fun selectTabInPanel(tabId: String, panelId: String) = Unit
    }

    private object ThrowingSplitViewOperations : FakeSplitViewOperations(supports = false) {
        override val supportsOpenPanelAsTab: Boolean get() = error("this host's provider is broken")
    }

    @Test
    fun `the chosen route is the one actually invoked`() {
        val ops = FakeSplitViewOperations(supports = true)
        val panel = PanelId("codebase", 1)

        // What the ViewModel does for PanelLaunchRoute.OPEN_PANEL_AS_TAB.
        assertEquals(PanelLaunchRoute.OPEN_PANEL_AS_TAB, panelLaunchRoute(supportsOpenPanelAsTab(ops), true, true))
        ops.openPanelAsTab(panel)

        assertEquals(panel, ops.promoted)
        assertNull(ops.openedTab, "the reflective tier's openTab must not also run")
    }
}
