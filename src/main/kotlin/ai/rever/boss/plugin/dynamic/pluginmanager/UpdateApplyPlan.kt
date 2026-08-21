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
 * Toolbox's own plugin id.
 *
 * Applying an update to this one is different in kind from any other: the force-unload disposes
 * the plugin that is doing the applying, cancelling the very coroutine mid-call. See
 * [selfLast] for how that is handled and why the cancellation is a success signal.
 */
const val TOOLBOX_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.pluginmanager"

/**
 * Plugins a running Toolbox cannot hot-reload, so their updates only apply on
 * a full restart:
 * - The API plugin: normally offered as [UpdateApplyPlan.SwapApiLayer]; kept
 *   here as the fallback when its loaded entry can't be resolved.
 * - The microkernel runtime: a classpath component, never a loadable plugin.
 *
 * **Toolbox itself is deliberately NOT here any more.** It was, because a force-unload cancelled
 * the coroutine driving its own reload and left it unloaded. The host fixed that by running
 * reloads in a scope detached from the caller (`PluginLoaderDelegateImpl.reloadPlugin`, mirroring
 * the API-layer swap), so the reload now completes even though the caller is torn down partway
 * through it.
 *
 * The old note here said to gate on host version before removing it. **The gate already exists
 * and is the manifest**: `minBossVersion` is 9.4.2, while the detached reload has shipped in every
 * release from 9.2.53 onward - so a host new enough to run this build of Toolbox is necessarily
 * new enough to reload it. A separate runtime check would only restate that, and the host has no
 * app-version system property for a plugin to read anyway (it publishes `boss.api.version` and
 * `boss.ipc.version`, neither of which tracks this).
 */
private val RESTART_ONLY_PLUGIN_IDS = setOf(
    API_PLUGIN_ID,
    "ai.rever.boss.microkernel.runtime",
)

/**
 * Split [ids] so Toolbox is applied last, and say whether it is in the batch at all.
 *
 * Applying Toolbox's own update force-unloads it, which cancels the coroutine calling
 * `reloadPlugin`/`resetPluginInstances`. The host's side of that call is detached, so the work
 * finishes regardless - but the CALLER does not survive to see it. That has two consequences a
 * caller has to respect:
 *
 * 1. Do the others FIRST, and report on them before touching Toolbox. Anything after the self
 *    apply may never run.
 * 2. Do not `runCatching` the self apply and read its result as success or failure. The
 *    cancellation IS the expected outcome, and reporting it as a failure is how a working
 *    self-update ends up showing "could not be hot-reloaded, restart BOSS" - from a classloader
 *    that is already closing.
 */
fun selfLast(ids: List<String>): Pair<List<String>, Boolean> =
    ids.filterNot { it == TOOLBOX_PLUGIN_ID } to ids.contains(TOOLBOX_PLUGIN_ID)

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
