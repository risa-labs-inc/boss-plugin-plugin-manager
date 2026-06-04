package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate

/**
 * What (if anything) is needed for a just-applied plugin update to take effect.
 * Shared by the panel's post-update dialog ([PluginManagerViewModel]) and the
 * background update toast flow ([UpdatePromptService]).
 */
sealed class UpdateApplyPlan {
    /** System/locked plugins were updated on disk; only a full restart applies them. */
    data class Restart(val pluginIds: List<String>, val displayName: String) : UpdateApplyPlan()

    /** Updated plugins have running instances that must be reset (tabs/panels closed). */
    data class Reset(val pluginIds: List<String>, val displayName: String, val instanceCount: Int) : UpdateApplyPlan()

    /** Nothing further needed. */
    object None : UpdateApplyPlan()
}

/**
 * Build the apply plan for the given just-updated plugins. Plugins that only
 * apply on a full restart (system/locked) take priority and produce [UpdateApplyPlan.Restart];
 * otherwise plugins with running instances produce [UpdateApplyPlan.Reset].
 */
fun buildUpdateApplyPlan(pluginIds: List<String>, delegate: PluginLoaderDelegate?): UpdateApplyPlan {
    if (delegate == null || pluginIds.isEmpty()) return UpdateApplyPlan.None
    val loaded = delegate.getLoadedPlugins().associateBy { it.pluginId }

    val restartNeeded = pluginIds.filter { id ->
        loaded[id]?.let { it.isSystemPlugin || !it.canUnload } ?: false
    }
    if (restartNeeded.isNotEmpty()) {
        return UpdateApplyPlan.Restart(
            pluginIds = restartNeeded,
            displayName = displayNameFor(restartNeeded, loaded)
        )
    }

    // Count once per plugin (each call walks the split tree) and keep only those
    // with at least one running instance.
    val counts = pluginIds.associateWith { delegate.getRunningInstanceCount(it) }
        .filterValues { it > 0 }
    if (counts.isEmpty()) return UpdateApplyPlan.None
    return UpdateApplyPlan.Reset(
        pluginIds = counts.keys.toList(),
        displayName = displayNameFor(counts.keys.toList(), loaded),
        instanceCount = counts.values.sum()
    )
}

private fun displayNameFor(ids: List<String>, loaded: Map<String, LoadedPluginInfo>): String =
    if (ids.size == 1) (loaded[ids[0]]?.displayName ?: ids[0]) else "${ids.size} plugins"
