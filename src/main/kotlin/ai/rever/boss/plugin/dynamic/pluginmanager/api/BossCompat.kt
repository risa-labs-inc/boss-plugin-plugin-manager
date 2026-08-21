package ai.rever.boss.plugin.dynamic.pluginmanager.api

/**
 * Judges whether a plugin version's declared `minBossVersion` is compatible with this host.
 *
 * The sibling of [IpcCompat], for the floor that actually stops a plugin loading. `minBossVersion`
 * is checked by `DynamicPluginLoader` and a version above this host's is refused outright with
 * `PluginBossVersionException` - so a store row above the floor is not merely risky to install, it
 * cannot run.
 *
 * **Why this did not exist until now.** The Toolbox has always carried the data: `PluginStoreItem`
 * has a `minBossVersion` field, and the Toolbox itself writes it when publishing. What it did not
 * have was the other side of the comparison - the host published `boss.api.version` and
 * `boss.ipc.version` but never its own app version, so there was nothing to compare against and
 * every published version looked installable.
 *
 * The consequence was a silent one. fluck-browser 1.2.22 declares `minBossVersion: 9.4.23`; on
 * 9.4.22 the Toolbox offered it, Install downloaded it, the loader refused it, and the only
 * explanation was one ERROR line in the log. For fluck-browser in particular that means the browser
 * tab disappears, so "Install did nothing" is the mildest way it presents.
 *
 * The host now publishes `boss.app.version` and this reads it, on exactly the terms [IpcCompat]
 * established: an absent property is [Status.UNKNOWN] and stays installable. That fallback is not
 * laziness, it is the only correct behaviour here - this same plugin has to keep working on hosts
 * that predate the property, and blocking every install on an older host to prevent a subset of
 * failed ones would be a worse bug than the one being fixed.
 */
object BossCompat {
    enum class Status {
        /** This host meets the floor. */
        COMPATIBLE,

        /** The version needs a newer BOSS than this one. The loader will refuse it. */
        REQUIRES_HOST_UPDATE,

        /**
         * Blank `minBossVersion`, or a host that does not publish its version.
         *
         * Treated as installable. There is no MAJOR_MISMATCH counterpart to [IpcCompat]'s, because
         * `minBossVersion` is a floor rather than a contract: a newer host is always acceptable, so
         * "different major" is not a failure mode the loader recognises.
         */
        UNKNOWN,
    }

    /** This host's app version, or null when it does not publish one. */
    val hostVersion: String?
        get() = System.getProperty("boss.app.version")?.takeIf { it.isNotBlank() }

    fun status(minBossVersion: String?): Status {
        if (minBossVersion.isNullOrBlank()) return Status.UNKNOWN
        val host = SemVer.parse(hostVersion ?: return Status.UNKNOWN) ?: return Status.UNKNOWN
        val required = SemVer.parse(minBossVersion) ?: return Status.UNKNOWN
        // A FLOOR: equal satisfies it. Reading this as strict would refuse the exact release built
        // to carry the plugin - 9.4.23 does satisfy "9.4.23 or later".
        return if (compare(host, required) >= 0) Status.COMPATIBLE else Status.REQUIRES_HOST_UPDATE
    }

    /** Installable when this host meets the floor, or when the floor cannot be judged. */
    fun isInstallable(minBossVersion: String?): Boolean =
        status(minBossVersion).let { it == Status.COMPATIBLE || it == Status.UNKNOWN }

    /**
     * What to tell the user, or null when there is nothing to say.
     *
     * Names both versions on purpose. "Needs a newer BOSS" leaves someone to guess whether their
     * update would even help; "Needs BOSS 9.4.23 (you have 9.4.22)" is actionable.
     */
    fun requirement(minBossVersion: String?): String? =
        when (status(minBossVersion)) {
            Status.REQUIRES_HOST_UPDATE ->
                "Needs BOSS $minBossVersion" + (hostVersion?.let { " (you have $it)" } ?: "")

            Status.COMPATIBLE, Status.UNKNOWN -> null
        }

    private fun compare(
        a: Triple<Int, Int, Int>,
        b: Triple<Int, Int, Int>,
    ): Int =
        when {
            a.first != b.first -> a.first - b.first
            a.second != b.second -> a.second - b.second
            else -> a.third - b.third
        }
}
