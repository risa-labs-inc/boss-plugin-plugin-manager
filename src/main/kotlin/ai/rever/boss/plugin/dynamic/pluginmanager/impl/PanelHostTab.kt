package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The host's own tab type for rendering a sidebar panel inside a main-area tab —
 * what its header menu calls "Open as Tab", and what a drag-out onto the centre
 * does. Registered once per window at startup.
 */
private val PANEL_HOST_TAB_TYPE = TabTypeId("panel-host")

/** The host-internal [TabInfo] that [PANEL_HOST_TAB_TYPE]'s factory requires. */
private const val PANEL_HOST_TAB_INFO_CLASS =
    "ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo"

/**
 * Build the host's `PanelHostTabInfo` for [panel], so a panel plugin can be opened
 * in the MAIN view instead of revealed in the sidebar. Null when this host cannot
 * do it, which the caller must treat as "fall back to the sidebar".
 *
 * ## This is now the FALLBACK tier, not the goal
 *
 * boss-plugin-api 1.0.77 added `SplitViewOperations.openPanelAsTab` (BossConsole#177)
 * and the caller prefers it wherever [supportsOpenPanelAsTab] says the host has it -
 * that route is the host's own move, so the panel keeps its state, which this one
 * cannot manage (see the ordering note in the caller). What is left here serves hosts
 * pinned below that api, which this plugin still has to run on: it is a system plugin
 * and its manifest floor deliberately did not move.
 *
 * Delete it when `minBossVersion` reaches the BossConsole release that carries
 * `openPanelAsTab`, not before - at that point no supported host needs it.
 *
 * ## Why this is reflective
 *
 * The capability exists and is exactly right — the host renders the panel's own
 * cached component in a main tab and tracks it as hosted-there, so its sidebar
 * icon focuses the tab afterwards rather than opening a second copy. But it is
 * reachable only from inside the host: `PanelHostTabComponent` casts its config
 * to the concrete `PanelHostTabInfo`, so handing [SplitViewOperations.openTab] a
 * plugin-side [TabInfo] carrying the same `typeId` would land in that cast and
 * throw. On the hosts this tier still serves - anything pinned to boss-plugin-api
 * 1.0.76 or below - nothing in the api exposes a promote-to-tab call, so there is
 * no supported construction path for it. (BossConsole#177 also made that cast a
 * checked one, so the same attempt on a newer host is a dead tab rather than a
 * throw; it is still not a way to open anything.)
 *
 * The classloader comes from the registered tab-type OBJECT rather than from
 * this plugin: host classes live in the app classloader, which is not the one
 * that loaded us, and `Class.forName` on our own loader would never find it.
 *
 * ## Consequences
 *
 * This binds to a host-internal name and constructor shape, so it is checked at
 * every call and degrades rather than throws: a host that predates the feature,
 * or one that renames the class, simply returns null here and the caller reveals
 * the panel in the sidebar as before. That degradation is the whole reason it can
 * stay while the supported route rolls out.
 */
fun panelHostTabInfo(tabRegistry: TabRegistry?, panel: PanelInfo): TabInfo? {
    val hostTabType = tabRegistry?.getTabTypeInfo(PANEL_HOST_TAB_TYPE) ?: return null
    return runCatching {
        Class.forName(PANEL_HOST_TAB_INFO_CLASS, true, hostTabType.javaClass.classLoader)
            .getConstructor(PanelId::class.java, String::class.java, ImageVector::class.java)
            .newInstance(panel.id, panel.displayName, panel.icon) as? TabInfo
    }.getOrNull()
}
