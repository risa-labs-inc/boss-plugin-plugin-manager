package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BlockedUpdateNotice
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.UpdateCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes tab, manual, and background checks so an older response cannot overwrite a newer one. */
internal class UpdateChecker(
    private val state: MutableStateFlow<PluginManagerState>,
    private val fetch: suspend () -> Result<UpdateCandidates>,
    private val reportFailure: (Exception) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun check() = mutex.withLock {
        state.update { it.copy(isCheckingUpdates = true, updatesError = null) }
        try {
            val candidates = fetch().getOrThrow()
            val updateInfos = candidates.loadable.map { (pluginId, newVersion) ->
                val installed = state.value.installedPlugins.find { it.pluginId == pluginId }
                UpdateInfo(
                    pluginId = pluginId,
                    displayName = installed?.displayName ?: pluginId,
                    currentVersion = installed?.version ?: "",
                    newVersion = newVersion
                )
            }
            // The held-back ones travel with them. Filtering them out and saying nothing would
            // leave a user on an out-of-date host reading "All plugins are up to date" while
            // updates they cannot have go unmentioned - a different silence, not a fix for the one
            // this replaced.
            val blocked = candidates.blockedByHost.map { held ->
                val installed = state.value.installedPlugins.find { it.pluginId == held.pluginId }
                BlockedUpdateNotice(
                    displayName = installed?.displayName ?: held.pluginId,
                    newVersion = held.version,
                    requiredBossVersion = held.requiredBossVersion
                )
            }
            state.update {
                it.copy(
                    updates = updateInfos,
                    blockedUpdates = blocked,
                    updatesError = null,
                    updatesLastChecked = now()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportFailure(e)
            // Transport messages can contain request details and are not suitable for the UI.
            state.update { it.copy(updatesError = "Couldn't check for updates. Please try again.") }
        } finally {
            state.update { it.copy(isCheckingUpdates = false) }
        }
    }
}

internal data class UpdatesEmptyState(val message: String, val description: String)

internal fun updatesEmptyState(
    isChecking: Boolean,
    error: String?,
    lastChecked: Long?,
    hasBlockedUpdates: Boolean,
): UpdatesEmptyState = when {
    isChecking -> UpdatesEmptyState("Checking for updates…", "Please wait for the check to finish")
    error != null -> UpdatesEmptyState("Couldn't check for updates", "Try refreshing to check again")
    lastChecked == null -> UpdatesEmptyState("Updates haven't been checked yet", "Refresh to check for updates")
    hasBlockedUpdates -> UpdatesEmptyState("No updates you can install yet", "The updates above need a newer BOSS")
    else -> UpdatesEmptyState("All plugins are up to date", "No updates available")
}
