package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate

/**
 * What (if anything) is needed for a just-applied plugin update to take effect.
 * Shared by the panel's post-update dialog ([PluginManagerViewModel]) and the
 * background update toast flow ([UpdatePromptService]).
 */
sealed class UpdateApplyPlan {
    /** Plugins the running Toolbox cannot hot-reload; only a full restart applies them. */
    data class Restart(val pluginIds: List<String>, val displayName: String) : UpdateApplyPlan()

    /** Updated plugins have running instances that must be reset (tabs/panels closed). */
    data class Reset(val pluginIds: List<String>, val displayName: String, val instanceCount: Int) : UpdateApplyPlan()

    /**
     * System/locked plugins whose new JAR is on disk while the old version is
     * still loaded, and with no running instances to disturb — hot-reload them
     * right away, no prompt needed.
     */
    data class Reload(val pluginIds: List<String>, val displayName: String) : UpdateApplyPlan()

    /**
     * The API plugin's new JAR is on disk; loading it hot-swaps the process-wide
     * API layer — every plugin unloads and reloads (open plugin tabs/panels
     * reset), which also applies any other pending on-disk updates. Disruptive
     * enough to sit behind a prompt rather than auto-run.
     */
    data class SwapApiLayer(val jarPath: String, val displayName: String) : UpdateApplyPlan()

    /** Nothing further needed. */
    object None : UpdateApplyPlan()
}

/** The plugin whose JAR carries the shared API layer. */
const val API_PLUGIN_ID = "ai.rever.boss.plugin.api"

/**
 * Plugins a running Toolbox cannot hot-reload, so their updates only apply on
 * a full restart:
 * - Toolbox itself: the force-unload disposes this plugin — cancelling the very
 *   coroutine driving the reload — before the new version could be loaded.
 *   (Goes away once the host runs reloads in a detached scope, like the #848
 *   API swap does; gate on host version before removing.)
 * - The API plugin: normally offered as [UpdateApplyPlan.SwapApiLayer]; kept
 *   here as the fallback when its loaded entry can't be resolved.
 * - The microkernel runtime: a classpath component, never a loadable plugin.
 */
private val RESTART_ONLY_PLUGIN_IDS = setOf(
    "ai.rever.boss.plugin.dynamic.pluginmanager",
    API_PLUGIN_ID,
    "ai.rever.boss.microkernel.runtime",
)

/**
 * Build the apply plan for the given just-updated plugins.
 *
 * System/locked plugins only had their JAR replaced on disk (the update flow
 * can't unload them in place), so the old version is still loaded and they
 * need a force reload even with zero running instances. Most of them can be
 * hot-reloaded via [PluginLoaderDelegate.reloadPlugin]; only the
 * [RESTART_ONLY_PLUGIN_IDS] (which would tear down Toolbox itself mid-reload)
 * produce [UpdateApplyPlan.Restart]. Plugins with running instances produce
 * [UpdateApplyPlan.Reset] — resetting force-reloads, so it also applies any
 * pending on-disk update.
 */
fun buildUpdateApplyPlan(pluginIds: List<String>, delegate: PluginLoaderDelegate?): UpdateApplyPlan {
    if (delegate == null || pluginIds.isEmpty()) return UpdateApplyPlan.None
    val loaded = delegate.getLoadedPlugins().associateBy { it.pluginId }

    fun isLocked(id: String) = loaded[id]?.let { it.isSystemPlugin || !it.canUnload } ?: false

    // An API-plugin update whose JAR only landed on disk: offer the API-layer
    // swap. It reloads every plugin from its current JAR, so it also applies
    // every other pending update in this batch — no need to plan them separately.
    if (API_PLUGIN_ID in pluginIds && isLocked(API_PLUGIN_ID)) {
        val api = loaded.getValue(API_PLUGIN_ID)
        if (api.jarPath.isNotBlank()) {
            return UpdateApplyPlan.SwapApiLayer(
                jarPath = api.jarPath,
                displayName = api.displayName
            )
        }
    }

    val restartNeeded = pluginIds.filter { it in RESTART_ONLY_PLUGIN_IDS && isLocked(it) }
    if (restartNeeded.isNotEmpty()) {
        return UpdateApplyPlan.Restart(
            pluginIds = restartNeeded,
            displayName = displayNameFor(restartNeeded, loaded)
        )
    }

    val reloadPending = pluginIds.filter { isLocked(it) }

    // Count once per plugin (each call walks the split tree) and keep only those
    // with at least one running instance.
    val counts = pluginIds.associateWith { delegate.getRunningInstanceCount(it) }
        .filterValues { it > 0 }
    if (counts.isNotEmpty()) {
        // Resetting force-reloads, so folding in the pending reloads lets one
        // action apply everything.
        val ids = (counts.keys + reloadPending).toList()
        return UpdateApplyPlan.Reset(
            pluginIds = ids,
            displayName = displayNameFor(ids, loaded),
            instanceCount = counts.values.sum()
        )
    }
    if (reloadPending.isNotEmpty()) {
        return UpdateApplyPlan.Reload(
            pluginIds = reloadPending,
            displayName = displayNameFor(reloadPending, loaded)
        )
    }
    return UpdateApplyPlan.None
}

private fun displayNameFor(ids: List<String>, loaded: Map<String, LoadedPluginInfo>): String =
    if (ids.size == 1) (loaded[ids[0]]?.displayName ?: ids[0]) else "${ids.size} plugins"
