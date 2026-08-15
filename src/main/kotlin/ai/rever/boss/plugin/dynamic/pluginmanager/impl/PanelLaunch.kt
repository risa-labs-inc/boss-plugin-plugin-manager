package ai.rever.boss.plugin.dynamic.pluginmanager.impl

/**
 * A registered panel or tab type, reduced to the three strings that identify it.
 *
 * A flattened copy rather than `PanelInfo` / `TabTypeInfo` themselves: the only
 * fields the match needs are strings, and depending on those interfaces would
 * drag an `ImageVector` — and therefore a Compose runtime — into the decision
 * and its tests.
 */
data class RegisteredSurface(
    /** `PanelId.panelId` or `TabTypeId.typeId`. */
    val surfaceId: String,
    /** The `pluginId` FIELD of that id, which is not necessarily the plugin that registered it. */
    val ownerPluginId: String,
    val displayName: String,
)

/**
 * The `PanelId.pluginId` / `TabTypeId.pluginId` default.
 *
 * Almost every plugin leaves it alone — `PanelId("git-log", 15)`,
 * `TabTypeId("editor")` — which is why owner-id equality alone resolves
 * only the handful (docker, kubernetes, organisation, dna-origami) that pass
 * a real plugin id.
 */
const val DEFAULT_SURFACE_OWNER: String = "ai.rever.boss"

/** Lowercase, letters and digits only: `top-of-mind`, `rpa_engine` and `Git Log` all collapse. */
private fun squash(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

/**
 * How the Toolbox will open a panel plugin, once its panel is resolved.
 *
 * Ordered best to worst, and the order is the point: the tiers differ in what survives the
 * trip, not only in whether they work.
 */
enum class PanelLaunchRoute {
    /**
     * `SplitViewOperations.openPanelAsTab` - the host's own move. The panel's cached component
     * is reused, so its state comes along, the sidebar copy is collapsed without being
     * destroyed, and the sidebar icon afterwards focuses the tab instead of opening a second
     * copy. Needs boss-plugin-api 1.0.77 pinned by the host (BossConsole#177).
     */
    OPEN_PANEL_AS_TAB,

    /**
     * The reflective panel-host tab: close the sidebar copy, then `openTab`. Reaches the main
     * view on older hosts, but the plugin-facing close drops the cached component, so the
     * panel starts fresh.
     */
    PANEL_HOST_TAB,

    /** Reveal it in the sidebar. Not the main view, but still opening the tool. */
    SIDEBAR_REVEAL,

    /** No provider here can open it; the caller falls through to the plugin's homepage. */
    NONE,
}

/**
 * Pick the route, given what this host actually offers.
 *
 * A separate pure function for the same reason [resolveLaunchSurface] is one: every wrong
 * answer here still renders an ordinary button and still opens *something*, so the failure is
 * silent. Getting the ORDER wrong is the specific silent failure - falling to
 * [PanelLaunchRoute.PANEL_HOST_TAB] on a host that supports the real call would quietly go on
 * resetting each panel's state, which is the bug the api addition exists to end.
 *
 * @param hostSupportsOpenPanelAsTab probed with `supportsOpenPanelAsTab`, never assumed from a
 *   version number - see that function for why the host's pinned api is what decides.
 * @param canBuildPanelHostTab `panelHostTabInfo` returned a usable config.
 * @param canRevealInSidebar there is a panel event provider and a window to target.
 */
fun panelLaunchRoute(
    hostSupportsOpenPanelAsTab: Boolean,
    canBuildPanelHostTab: Boolean,
    canRevealInSidebar: Boolean,
): PanelLaunchRoute =
    when {
        hostSupportsOpenPanelAsTab -> PanelLaunchRoute.OPEN_PANEL_AS_TAB
        canBuildPanelHostTab -> PanelLaunchRoute.PANEL_HOST_TAB
        canRevealInSidebar -> PanelLaunchRoute.SIDEBAR_REVEAL
        else -> PanelLaunchRoute.NONE
    }

/**
 * Find the panel or tab type to open for an installed plugin.
 *
 * There is no host API that answers "which surfaces did plugin X register" —
 * the host tracks it internally (PluginRegistrationTracker) but exposes nothing
 * to plugins, and the manifest's optional `panel` block is declared by almost
 * none of them. So this matches the live registry against the plugin, most
 * reliable signal first:
 *
 *  1. The surface names the plugin as its owner. Exact, and exactly what the
 *     previous owner-id-only lookup did — kept as the first tier, not replaced.
 *  2. The surface id matches the last segment of the plugin id once separators
 *     are squashed: `…dynamic.topofmind` ↔ `top-of-mind`,
 *     `…dynamic.rpaengine` ↔ `rpa_engine`. This is the convention essentially
 *     every plugin follows, and the tier that makes Open work at all for the
 *     ~90% that never pass a plugin id.
 *  3. The surface's display name matches the plugin's — for the stragglers
 *     whose id follows no convention (flow-tab registers `flow-launcher`).
 *
 * Tiers 2 and 3 only consider surfaces that have NOT named a different owner:
 * a surface that declared its plugin failed tier 1 for a reason, and inferring
 * past that would hand one plugin another's panel. They also require a UNIQUE
 * hit — an ambiguous match means we do not know, and offering no Open button
 * beats opening the wrong tool.
 *
 * Returns null when nothing matches, which is the correct answer for the many
 * plugins that contribute only MCP tools or background services.
 */
fun <T> resolveLaunchSurface(
    pluginId: String,
    displayName: String,
    surfaces: List<T>,
    identity: (T) -> RegisteredSurface,
): T? {
    surfaces.firstOrNull { identity(it).ownerPluginId == pluginId }?.let { return it }

    val unclaimed = surfaces.filter { identity(it).ownerPluginId == DEFAULT_SURFACE_OWNER }

    val slug = squash(pluginId.substringAfterLast('.'))
    if (slug.isNotEmpty()) {
        unclaimed.singleOrNull { squash(identity(it).surfaceId) == slug }?.let { return it }
    }

    val name = squash(displayName)
    if (name.isNotEmpty()) {
        unclaimed.singleOrNull { squash(identity(it).displayName) == name }?.let { return it }
    }

    return null
}
