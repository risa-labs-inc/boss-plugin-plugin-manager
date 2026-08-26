package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.isVersionNewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background service that proactively prompts the user (via host toasts) when
 * new IPC-compatible plugin updates are available, and applies them directly
 * from the toast's action button.
 *
 * Prompt-once-per-version: each (pluginId, newVersion) pair is recorded at
 * show time (the host toast has no dismiss callback), so a dismissed prompt
 * never re-appears for the same version — only for a newer one. A failed
 * update clears its record so the next detection cycle re-prompts.
 *
 * All host providers are nullable and handled gracefully: without a
 * [NotificationProvider] the service no-ops; without a [PluginStorageProvider]
 * dedupe falls back to in-memory (per-session) tracking.
 */
class UpdatePromptService(
    private val scope: CoroutineScope,
    private val apiImpl: PluginManagerAPIImpl,
    private val loaderDelegate: PluginLoaderDelegate?,
    private val notifications: NotificationProvider?,
    private val storage: PluginStorageProvider?
) {

    @Serializable
    data class PromptRecord(val version: String, val promptedAt: Long)

    @Serializable
    private data class PromptRecords(val records: Map<String, PromptRecord> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val inMemoryRecords = mutableMapOf<String, PromptRecord>()

    @Volatile
    private var busy = false

    /**
     * The prompt on screen: its notification id and what it offered, as ONE value.
     *
     * Two volatile fields written in a careful order were still never read as a unit,
     * so the collector could compute "satisfied" from one prompt's offers and then
     * dismiss whatever id had arrived since - a new toast vanishing just after it
     * appeared, with nothing to bring it back until the next check.
     */
    private data class Prompt(
        val id: String,
        val offered: Map<String, String>
    )

    @Volatile
    private var prompt: Prompt? = null

    private val watching = AtomicBoolean(false)


    companion object {
        private const val STORAGE_KEY = "updatePrompts"
    }

    /**
     * Check for compatible updates and show a prompt for any not yet
     * prompted at their current latest version. Safe to call repeatedly
     * (startup, realtime events) — dedupe makes extra calls harmless.
     */
    suspend fun checkAndPrompt() {
        val notifications = notifications ?: return
        if (busy) return
        val updates = runCatching { apiImpl.checkForCompatibleUpdates() }.getOrDefault(emptyList())
        if (updates.isEmpty()) return

        val fresh = mutex.withLock {
            val records = loadRecords()
            updates.filter { records[it.pluginId]?.version != it.newVersion }
                .also { toPrompt ->
                    if (toPrompt.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        saveRecords(records + toPrompt.associate {
                            it.pluginId to PromptRecord(it.newVersion, now)
                        })
                    }
                }
        }
        if (fresh.isEmpty()) return

        // Replace any prior prompt still on screen
        prompt?.let { notifications.dismiss(it.id) }
        val shown = if (fresh.size == 1) {
            val u = fresh[0]
            notifications.showToast(
                message = "${u.displayName} ${u.currentVersion} → ${u.newVersion}",
                type = NotificationType.INFO,
                duration = NotificationDuration.INDEFINITE,
                title = "Plugin update available",
                actionLabel = "Update",
                onAction = { performUpdate(fresh) }
            )
        } else {
            notifications.showToast(
                message = fresh.joinToString(", ") { it.displayName },
                type = NotificationType.INFO,
                duration = NotificationDuration.INDEFINITE,
                title = "${fresh.size} plugin updates available",
                actionLabel = "Update All",
                onAction = { performUpdate(fresh) }
            )
        }
        // One assignment, once the toast exists: the collector reads this as a unit, so
        // it can no longer act on one prompt's offers and dismiss another prompt's id.
        val current = Prompt(shown, fresh.associate { it.pluginId to it.newVersion })
        prompt = current

        // The collector cannot cover the window between showToast returning and the
        // line above: it saw an empty map, returned, and observeInstalledPlugins has no
        // reason to emit again - so an update landing in that gap left an INDEFINITE
        // toast offering a version already installed until the next refresh. Asked once
        // more here, where the map is finally set.
        val installedNow = apiImpl.getInstalledPlugins().associate { it.pluginId to it.version }
        if (promptSatisfied(current.offered, installedNow)) dismissPrompt(current)
    }

    /**
     * Retire the prompt once every plugin it named has reached the version it
     * offered, whoever installed it.
     *
     * Watches the installed list rather than this plugin's own update path, so an
     * update applied from the Toolbox panel or another window retires the toast
     * too - not only one applied from the toast itself.
     *
     * What it does NOT guarantee: the list is this plugin's own view, refreshed by
     * its install paths and by the startup and store-change hooks in
     * `PluginManagerCore.start()`. An update performed entirely host-side (the
     * host's own "Update Available" prompt) is picked up at the next refresh rather
     * than immediately, so the toast can outlive it briefly.
     *
     * Started once, from [PluginManagerCore.start]; the collector lives for as
     * long as the plugin does.
     */
    fun watchForApplied() {
        // Once. PluginManagerCore.start() is called once today, so this is a guard
        // against a future second caller stacking collectors rather than a live bug.
        if (!watching.compareAndSet(false, true)) return
        scope.launch {
            apiImpl.observeInstalledPlugins().collect { installed ->
                // Read ONCE, and only retired if it is still the prompt on screen.
                val snapshot = prompt ?: return@collect
                val versions = installed.associate { it.pluginId to it.version }
                if (promptSatisfied(snapshot.offered, versions)) dismissPrompt(snapshot)
            }
        }
    }

    /**
     * Take [target] off screen, if it is still the prompt on screen.
     *
     * Naming which prompt is the point: a collector that decided on one prompt's
     * offers must not dismiss whatever arrived since.
     */
    private fun dismissPrompt(target: Prompt?) {
        val current = prompt ?: return
        if (target != null && target !== current) return
        notifications?.dismiss(current.id)
        prompt = null
    }

    /** Apply the prompted updates; invoked from the toast's action button. */
    private fun performUpdate(targets: List<UpdateInfo>) {
        if (busy) return
        busy = true
        // Unconditional: the user pressed the button on whatever is showing.
        dismissPrompt(null)

        scope.launch {
            try {
                val succeeded = mutableListOf<String>()
                val failed = mutableListOf<UpdateInfo>()
                // Three buckets, not two. A cancel is neither: the host's download
                // dialog offers Cancel on exactly this path, and calling it a failure
                // told the user "Failed to update: Docker" for the thing they had just
                // asked to stop - with an Update All toast, naming that one plugin as
                // failed while the rest succeeded.
                val cancelled = mutableListOf<UpdateInfo>()
                for (target in targets) {
                    val result = runCatching { apiImpl.updatePlugin(target.pluginId) }
                        .getOrElse { InstallResult.LoadFailed(it.message ?: "Unknown error") }
                    when {
                        result is InstallResult.Success -> succeeded.add(target.pluginId)
                        result.wasCancelled() -> cancelled.add(target)
                        else -> failed.add(target)
                    }
                }

                // Both are re-offered next cycle: a cancelled update has not happened
                // either, so keeping its record would silence the prompt for a version
                // the user still does not have.
                if (cancelled.isNotEmpty()) {
                    mutex.withLock {
                        saveRecords(loadRecords() - cancelled.map { it.pluginId }.toSet())
                    }
                }

                if (failed.isNotEmpty()) {
                    // Allow re-prompting for failed updates on the next cycle
                    mutex.withLock {
                        saveRecords(loadRecords() - failed.map { it.pluginId }.toSet())
                    }
                    notifications?.showError(
                        "Failed to update: ${failed.joinToString(", ") { it.displayName }}"
                    )
                }

                if (succeeded.isNotEmpty()) {
                    showApplyFollowUp(succeeded)
                }
            } finally {
                busy = false
            }
        }
    }

    /** After a successful update, apply or surface the remaining step (if any) as a toast. */
    private fun showApplyFollowUp(succeeded: List<String>) {
        val notifications = notifications ?: return
        when (val plan = buildUpdateApplyPlan(succeeded, loaderDelegate)) {
            is UpdateApplyPlan.Reload -> scope.launch {
                // Toolbox last, and unreported: reloading it cancels this coroutine, so anything
                // after it may not run and its cancellation is success rather than failure.
                val (others, includesSelf) = selfLast(plan.pluginIds)
                val failed = others.filter { id ->
                    runCatching { loaderDelegate?.reloadPlugin(id) }.getOrNull() == null
                }
                if (failed.isEmpty()) {
                    notifications.showSuccess("${plan.displayName} updated")
                } else {
                    notifications.showToast(
                        message = "${plan.displayName} updated on disk but could not be " +
                            "hot-reloaded. Restart BOSS to apply.",
                        type = NotificationType.WARNING,
                        duration = NotificationDuration.INDEFINITE,
                        title = "Update installed",
                        actionLabel = "Restart BOSS",
                        onAction = { loaderDelegate?.restartApplication() }
                    )
                }
                if (includesSelf) loaderDelegate?.reloadPlugin(TOOLBOX_PLUGIN_ID)
            }
            is UpdateApplyPlan.SwapApiLayer -> notifications.showToast(
                message = "${plan.displayName} updated. Applying hot-swaps the API layer — " +
                    "all plugins reload and their open tabs reset.",
                type = NotificationType.SUCCESS,
                duration = NotificationDuration.INDEFINITE,
                title = "Update installed",
                actionLabel = "Apply Now",
                onAction = {
                    scope.launch {
                        // Loading the newer api jar triggers the host's detached
                        // API-layer swap, which unloads THIS plugin mid-flight —
                        // no outcome toast is possible; the visible full-plugin
                        // reload is the feedback.
                        runCatching { loaderDelegate?.loadPlugin(plan.jarPath) }
                    }
                }
            )
            is UpdateApplyPlan.Restart -> notifications.showToast(
                message = "${plan.displayName} updated. Restart BOSS to apply.",
                type = NotificationType.SUCCESS,
                duration = NotificationDuration.INDEFINITE,
                title = "Update installed",
                actionLabel = "Restart BOSS",
                onAction = { loaderDelegate?.restartApplication() }
            )
            is UpdateApplyPlan.Reset -> notifications.showToast(
                message = "${plan.displayName} updated. Reset ${plan.instanceCount} running " +
                    "instance${if (plan.instanceCount == 1) "" else "s"} to apply.",
                type = NotificationType.SUCCESS,
                duration = NotificationDuration.INDEFINITE,
                title = "Update installed",
                actionLabel = "Reset",
                onAction = {
                    scope.launch {
                        // Toolbox last: resetting it disposes this plugin, so any id after it
                        // would never be reset. See `selfLast`.
                        val (others, includesSelf) = selfLast(plan.pluginIds)
                        others.forEach { id ->
                            runCatching { loaderDelegate?.resetPluginInstances(id) }
                        }
                        if (includesSelf) loaderDelegate?.resetPluginInstances(TOOLBOX_PLUGIN_ID)
                    }
                }
            )
            is UpdateApplyPlan.None -> {
                val loaded = loaderDelegate?.getLoadedPlugins()?.associateBy { it.pluginId } ?: emptyMap()
                val name = if (succeeded.size == 1) {
                    loaded[succeeded[0]]?.displayName ?: succeeded[0]
                } else {
                    "${succeeded.size} plugins"
                }
                notifications.showSuccess("$name updated")
            }
        }
    }

    // ========================================
    // PROMPT RECORD PERSISTENCE
    // ========================================

    private suspend fun loadRecords(): Map<String, PromptRecord> {
        val storage = storage ?: return inMemoryRecords.toMap()
        return try {
            storage.getJson(STORAGE_KEY)
                ?.let { json.decodeFromString<PromptRecords>(it).records }
                ?: emptyMap()
        } catch (_: Exception) {
            inMemoryRecords.toMap()
        }
    }

    private suspend fun saveRecords(records: Map<String, PromptRecord>) {
        inMemoryRecords.clear()
        inMemoryRecords.putAll(records)
        val storage = storage ?: return
        try {
            storage.putJson(STORAGE_KEY, json.encodeToString(PromptRecords.serializer(), PromptRecords(records)))
        } catch (_: Exception) {
            // In-memory copy still dedupes for this session
        }
    }
}

/**
 * Whether a prompt offering [offered] has nothing left to offer, given the
 * [installed] versions.
 *
 * Every plugin it named, not any: an "Update All" toast still has something to
 * say while one of its plugins is behind, and dismissing on the first one to
 * land would drop the rest silently.
 *
 * An offered plugin that is absent from [installed] counts as not applied -
 * during an api hot swap every plugin is briefly unloaded, and taking that as
 * "done" would retire a prompt that is still true.
 */
internal fun promptSatisfied(
    offered: Map<String, String>,
    installed: Map<String, String>
): Boolean =
    offered.isNotEmpty() &&
        offered.all { (pluginId, version) ->
            val current = installed[pluginId] ?: return@all false
            // At least, not exactly. A toast offering 2.0.0 is just as stale once the
            // user installs 2.0.1 from the panel, and demanding equality left it on
            // screen for the session offering a version already passed - the same bug
            // class this whole path exists to close.
            current == version || isVersionNewer(current, version)
        }
