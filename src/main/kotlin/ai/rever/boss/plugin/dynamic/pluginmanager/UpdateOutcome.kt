package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo

/**
 * Which button an [InstallResult] came back to, for [outcomeErrorFor].
 *
 * The label differs, but so does the meaning of one variant - see `AlreadyInstalled` there - so
 * this is not just a message prefix.
 */
internal enum class PluginAction(val label: String) {
    INSTALL("Install"),
    UPDATE("Update"),

    /**
     * A version picked in the version sheet for a plugin that is already installed, which may be
     * a downgrade. It replaces the installed copy exactly as an update does, so it fails the same
     * ways; only the word differs, because "Install failed" or "Update failed" both misname a
     * rollback.
     */
    CHANGE_VERSION("Version change"),
}

/**
 * Why an outcome failed, with **no verb attached**, or null when it is not a failure.
 *
 * Split from [outcomeErrorFor] so a caller reporting on several plugins at once can compose the
 * cause into its own sentence instead of nesting a whole "Update failed: ..." inside one.
 * `updateAllPlugins` used to name the plugin and never the reason, which is the same silence
 * this change is about, one level up.
 *
 * **Exhaustive over [InstallResult] with no `else`**, because the bug being fixed was a missing
 * branch rather than a wrong one: `LoadFailed` fell into an `else` that cleared the spinner and
 * set no error, so a refused update was indistinguishable from a button that was never wired up.
 * An `else` would let the next variant added to the sealed class go silent the same way.
 */
internal fun failureReasonFor(
    result: InstallResult,
    action: PluginAction,
): String? =
    when (result) {
        is InstallResult.Success -> null
        is InstallResult.AlreadyInstalled ->
            when (action) {
                // Checked before any work happens, so nothing failed. It is still worth a word -
                // see [outcomeErrorFor] - but it is not a failure and must not be counted as one.
                PluginAction.INSTALL -> null
                // Not benign here. `updatePluginInternal` reaches `installPluginInternal` only
                // AFTER `uninstallPlugin` returned Success, and that function's first act is to
                // return AlreadyInstalled if the plugin is still registered. So this means the
                // unload reported success while the old version stayed - the update silently did
                // not happen, which is the exact symptom this change exists to stop hiding.
                PluginAction.UPDATE, PluginAction.CHANGE_VERSION ->
                    "version ${result.currentVersion} is still installed"
            }
        // A cancel is an answer, not a fault: the user pressed Cancel in the download
        // dialog. Answered here rather than at each button so the Update All banner
        // does not count it as a failure either.
        is InstallResult.DownloadFailed -> result.error.takeIf { it != DOWNLOAD_CANCELLED }
        is InstallResult.LoadFailed -> result.error
        // Not currently produced anywhere - nothing in PluginManagerAPIImpl constructs it, and
        // the IPC gate reports DownloadFailed instead. Handled because the sealed class allows
        // it and a future producer must not land back in a silent branch.
        is InstallResult.VersionConflict ->
            "needs version ${result.required}, but ${result.available} is available"
    }

/**
 * The message a single-plugin Install or Update button shows, or null for nothing to say.
 *
 * Every one of the five call sites routes through this, including the store tab's Install
 * button - which used to hand-roll all five branches with its own wording, so the canonical
 * decision and the highest-traffic button could disagree about the same outcome.
 */
internal fun outcomeErrorFor(
    result: InstallResult,
    action: PluginAction,
): String? {
    // Not a failure, so it has no reason - but the user pressed a button and nothing happened,
    // which is the shape of bug this whole change exists to stop. Wording kept from
    // installFromRemote, which has always said exactly this.
    if (result is InstallResult.AlreadyInstalled && action == PluginAction.INSTALL) {
        return "Plugin already installed (v${result.currentVersion})"
    }
    return failureReasonFor(result, action)?.let { reason -> "${action.label} failed: $reason" }
}

/**
 * The banner for a whole Update All run, or null when everything worked.
 *
 * Takes display-name-to-reason pairs. Naming the plugins without their causes was the old
 * behaviour and is what this exists to correct. Shared by the panel's Update All and the
 * background update toast (`UpdatePromptService`), so both say the same thing.
 *
 * Several failures go one per line. A comma-joined `Name (reason), Name (reason)` could not be
 * read back once a reason held a comma of its own, and [HOST_REFUSED_UNLOAD] does.
 */
internal fun updateAllError(failures: List<Pair<String, String>>): String? =
    when (failures.size) {
        0 -> null
        1 -> "Failed to update ${failures[0].first}: ${failures[0].second}"
        else -> "Failed to update:\n" + failures.joinToString("\n") { "- ${it.first}: ${it.second}" }
    }

/**
 * Why the host would not unload a plugin, as the tail of a failure sentence.
 *
 * One definition for both producers in `PluginManagerAPIImpl` (replacing an installed version,
 * and uninstalling), which had drifted apart by a clause. The host's own reasons cannot reach
 * here - its unload call answers with a bare Boolean and logs them as "Plugin unload refused" -
 * so this says where they are instead.
 */
internal const val HOST_REFUSED_UNLOAD =
    "the host refused to unload it (it may still be in use by another plugin; see the app log)"

/** The reason a replacement fails with when the installed copy could not be unloaded. */
internal fun couldNotReplace(reason: String): String = "could not replace the installed version - $reason"

/**
 * Where a failed version-sheet install reports: inside the sheet when it is still open for
 * [pluginId], otherwise the panel banner.
 *
 * The sheet is deliberately left open on failure so the user can pick another version, and it is
 * a dialog over the panel - so a message written to the panel banner was painted underneath the
 * dialog that produced it. The banner is only right once the sheet is gone or shows another
 * plugin.
 */
internal fun PluginManagerState.withVersionInstallError(
    pluginId: String,
    message: String?,
): PluginManagerState {
    val sheet = versionSheet
    return if (sheet != null && sheet.pluginId == pluginId) {
        copy(versionSheet = sheet.copy(installError = message))
    } else {
        copy(error = message)
    }
}

/**
 * The update rows that survive a run: drop what actually succeeded, keep everything else.
 *
 * Deliberately the inverse of "keep the ones we saw fail". The failure list is built from a
 * snapshot taken before the loop, while `updates` is re-read after it and the background poller
 * can write that field while the loop is suspended on network I/O - so filtering by the failed
 * set silently discards any row that arrived mid-run.
 */
internal fun remainingUpdates(
    current: List<UpdateInfo>,
    succeeded: Set<String>,
): List<UpdateInfo> = current.filterNot { it.pluginId in succeeded }
