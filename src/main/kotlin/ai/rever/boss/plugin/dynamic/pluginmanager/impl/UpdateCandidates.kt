package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.dynamic.pluginmanager.api.BossCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginUpdateRow

/**
 * The result of judging update candidates: what can be taken, and what was held back.
 *
 * [blockedByHost] exists because dropping those rows silently would recreate the problem this whole
 * change is about. Without it a user on an out-of-date host sees "All plugins are up to date" while
 * updates they cannot have go unmentioned - a different silence, not an improvement on the one it
 * replaced.
 */
internal data class UpdateCandidates(
    /** Plugin id to the version it can be updated to. */
    val loadable: Map<String, String> = emptyMap(),
    /** Newer versions held back because this host does not meet their `minBossVersion`. */
    val blockedByHost: List<BlockedUpdate> = emptyList(),
)

/** A newer version that exists but this host cannot load. */
internal data class BlockedUpdate(
    val pluginId: String,
    val version: String,
    val requiredBossVersion: String,
)

/**
 * Which installed plugins have a newer published version, split by whether this host can load it.
 *
 * Extracted from `checkForUpdatesResult` so the decision can be tested without Postgrest. It was
 * three lines inline, and being untestable is a large part of why it was wrong for so long: the
 * only check was "is it newer", so every newer version was offered regardless of what it required.
 *
 * Taking such an update is destructive rather than merely useless. The update path downloads over
 * the installed jar, so a version the loader refuses does not fail to arrive - it removes a working
 * plugin. That is how a file named `...fluck-browser-1.2.21.jar` came to hold 1.2.22 bytes that no
 * longer loaded, with the browser tab simply gone and one line in the host log to explain it.
 *
 * @param rows the catalogue's latest version per plugin, with its declared app floor
 * @param installedVersions the version currently installed, by plugin id
 */
internal fun loadableUpdates(
    rows: List<PluginUpdateRow>,
    installedVersions: Map<String, String>,
): UpdateCandidates {
    val loadable = mutableMapOf<String, String>()
    val blocked = mutableListOf<BlockedUpdate>()
    rows.forEach { row ->
        val latest = row.latestVersion ?: return@forEach
        val current = installedVersions[row.pluginId] ?: return@forEach
        // Newness first: a version that is not newer is not an update whatever its floor, and
        // reporting it as "blocked" would invent a problem the user does not have.
        if (!isNewerVersion(latest, current)) return@forEach
        // The floor fails OPEN: a blank column or a host that does not publish its version leaves
        // the update offered, exactly as before. This narrows what is offered, it does not make
        // offering conditional on new data being present.
        if (BossCompat.isInstallable(row.latestMinBossVersion)) {
            loadable[row.pluginId] = latest
        } else {
            blocked += BlockedUpdate(row.pluginId, latest, row.latestMinBossVersion ?: "")
        }
    }
    return UpdateCandidates(loadable = loadable, blockedByHost = blocked)
}

/**
 * Whether [newVersion] sorts above [currentVersion], comparing segment by segment as integers.
 *
 * Moved off `PluginManagerAPIImpl` unchanged, so [loadableUpdates] and its tests use the same
 * comparison the update check has always used rather than a second copy that could drift.
 */
internal fun isNewerVersion(
    newVersion: String,
    currentVersion: String,
): Boolean {
    val newParts = newVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
    val currentParts = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

    for (i in 0 until maxOf(newParts.size, currentParts.size)) {
        val newPart = newParts.getOrElse(i) { 0 }
        val currentPart = currentParts.getOrElse(i) { 0 }
        if (newPart > currentPart) return true
        if (newPart < currentPart) return false
    }
    return false
}
