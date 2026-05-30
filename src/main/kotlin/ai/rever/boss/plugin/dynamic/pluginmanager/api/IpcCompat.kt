package ai.rever.boss.plugin.dynamic.pluginmanager.api

/**
 * Judges whether a plugin version's declared `minIpcVersion` is compatible with
 * the host's IPC contract.
 *
 * The plugin-manager runs in-process but cannot depend on `:boss-ipc` (it's a
 * standalone plugin). The host publishes its `IpcVersion.CURRENT` as the
 * `boss.ipc.version` system property at startup (see PluginStoreSetup); this
 * helper reads it and applies the same rules as
 * `ai.rever.boss.ipc.IpcVersion.isCompatible`: same major required, and the
 * version's `minor.patch` must be ≤ the host's.
 *
 * When the property is absent (older host, or a build where IPC isn't exposed),
 * everything resolves to [Status.UNKNOWN] and is treated as installable.
 */
object IpcCompat {

    enum class Status {
        /** Host can load this version. */
        COMPATIBLE,

        /** Same major, but needs a newer host minor/patch. */
        REQUIRES_HOST_UPDATE,

        /** Different IPC major — hard incompatible. */
        MAJOR_MISMATCH,

        /** Blank minIpcVersion, or host IPC version unknown. */
        UNKNOWN
    }

    /** The host's IPC contract version, or null if it didn't publish one. */
    val hostVersion: String?
        get() = System.getProperty("boss.ipc.version")?.takeIf { it.isNotBlank() }

    fun status(minIpcVersion: String?): Status {
        if (minIpcVersion.isNullOrBlank()) return Status.UNKNOWN
        val host = parse(hostVersion ?: return Status.UNKNOWN) ?: return Status.UNKNOWN
        val rt = parse(minIpcVersion) ?: return Status.UNKNOWN
        return when {
            rt.first != host.first -> Status.MAJOR_MISMATCH
            rt.second > host.second -> Status.REQUIRES_HOST_UPDATE
            rt.second == host.second && rt.third > host.third -> Status.REQUIRES_HOST_UPDATE
            else -> Status.COMPATIBLE
        }
    }

    /** Installable when compatible, or unknown (legacy / no host IPC info). */
    fun isInstallable(minIpcVersion: String?): Boolean =
        status(minIpcVersion).let { it == Status.COMPATIBLE || it == Status.UNKNOWN }

    private fun parse(version: String): Triple<Int, Int, Int>? {
        val core = version.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }
}
