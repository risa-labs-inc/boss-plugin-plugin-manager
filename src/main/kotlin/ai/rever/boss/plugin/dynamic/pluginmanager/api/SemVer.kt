package ai.rever.boss.plugin.dynamic.pluginmanager.api

/**
 * `major.minor.patch` parsing, shared by every version floor in this plugin.
 *
 * One copy, because this change argues that a predicate duplicated across call sites is a predicate
 * that drifts - and [BossCompat] arrived with a `parse` byte-identical to [IpcCompat]'s. Two copies
 * of version arithmetic is exactly how two floors come to disagree about the same pair of numbers.
 */
internal object SemVer {
    /**
     * The three numeric components, or null when the string is not a version.
     *
     * Requires all three. A two-part "9.4" returns null rather than assuming a patch of 0: a floor
     * is not something to guess at, and every caller here treats null as "no opinion" and fails
     * open, which is the honest answer.
     *
     * Qualifiers are dropped, so `9.4.23-rc1` and `9.4.23+build.7` compare as `9.4.23`. A release
     * candidate must not decide compatibility, and must not make the comparison unparseable either.
     */
    fun parse(version: String): Triple<Int, Int, Int>? {
        val core = version.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }
}
