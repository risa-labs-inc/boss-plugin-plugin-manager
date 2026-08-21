package ai.rever.boss.plugin.dynamic.pluginmanager.api

/**
 * Whether this host can load a published version, across **both** floors it has to clear.
 *
 * One function rather than a condition in the row that renders it, because the two floors are
 * checked in four places (this row, the store card, the update filter, the download gate) and a
 * predicate that lives in a composable is one nobody can test. The version sheet's copy checked
 * only the IPC floor and so showed an Install button for versions `DynamicPluginLoader` refuses.
 *
 * The floors fail differently:
 * - [IpcCompat] governs whether a plugin's out-of-process half can speak to this host.
 * - [BossCompat] governs whether the jar loads at all, so it is the stricter consequence.
 *
 * Both fail open on UNKNOWN, which is what keeps the store usable on hosts that publish neither
 * property.
 */
fun PluginVersionInfo.isLoadableHere(): Boolean =
    // `compatibility`, NOT `IpcCompat.isInstallable(minIpcVersion)`. The two are built from
    // different inputs and disagree: `min_ipc_version` is nullable, and the entry coerces the
    // string to "1.0.0" while resolving the status from the raw null. So a version declaring no
    // IPC floor resolves to UNKNOWN (the badge shows nothing) while the string "1.0.0" would read
    // as MAJOR_MISMATCH on any host whose IPC major is not 1 - a row with no badge and no action.
    // Latent while host IPC stays on 1.x, and a divergence from the badge either way.
    compatibility != IpcCompat.Status.REQUIRES_HOST_UPDATE &&
        compatibility != IpcCompat.Status.MAJOR_MISMATCH &&
        bossCompatibility != BossCompat.Status.REQUIRES_HOST_UPDATE

/**
 * Why a version cannot be loaded here, or null when it can be.
 *
 * Prefers the app floor's message because it can name a version to update to; the IPC floor's
 * wording is deliberately vaguer, since "host IPC 1.4.0" is not something a user can act on.
 */
fun PluginVersionInfo.blockedReason(): String? =
    when {
        isLoadableHere() -> null
        else -> BossCompat.requirement(minBossVersion) ?: "Needs newer BOSS"
    }

/**
 * Why the store's **latest** version of this plugin cannot be installed here, or null when it can.
 *
 * Only the app floor, because that is all a catalogue row carries: `minBossVersion` comes from the
 * view's `latest_min_boss_version`, while the IPC floor lives on the individual version row and is
 * not projected into the list. So this answers the question the card can answer, and the version
 * sheet - which does fetch per-version rows - answers the fuller one.
 *
 * Judging the LATEST version is the right scope for a card whose button installs exactly that.
 */
fun PluginStoreItem.blockedReason(): String? = BossCompat.requirement(minBossVersion)

/**
 * A newer version of an installed plugin that this host cannot load, ready to render.
 *
 * The display-ready counterpart of the store-side `BlockedUpdate`: the same fact with the plugin's
 * name resolved, so the Updates tab does not have to reach back into the installed list to draw it.
 */
data class BlockedUpdateNotice(
    val displayName: String,
    val newVersion: String,
    val requiredBossVersion: String,
)
