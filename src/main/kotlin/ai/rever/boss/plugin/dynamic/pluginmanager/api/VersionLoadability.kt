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
    IpcCompat.isInstallable(minIpcVersion) &&
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
