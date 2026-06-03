package ai.rever.boss.plugin.dynamic.pluginmanager

import java.security.MessageDigest

/**
 * Client-side password gate for the admin "Delete from Store" action.
 *
 * The expected password's SHA-256 hash is injected at build time via
 * [BuildConfig.ADMIN_DELETE_PASSWORD_HASH] (see build.gradle.kts). The plaintext password is never
 * stored — the typed input is hashed and compared against the build-time hash.
 *
 * This guards against accidental/casual deletions and requires a known secret; it is not a defense
 * against someone who recompiles the plugin. Server-side admin RBAC on the delete endpoint is the
 * actual authorization boundary and is unchanged.
 */
object DeletePasswordGate {

    /** True when a delete password hash was baked into this build. */
    val isConfigured: Boolean
        get() = BuildConfig.ADMIN_DELETE_PASSWORD_HASH.isNotBlank()

    /** Returns true only when [input] hashes to the configured delete password hash. */
    fun verify(input: String): Boolean {
        if (!isConfigured) return false
        // Constant-time comparison on the hex strings.
        return MessageDigest.isEqual(
            sha256Hex(input).toByteArray(Charsets.US_ASCII),
            BuildConfig.ADMIN_DELETE_PASSWORD_HASH.lowercase().toByteArray(Charsets.US_ASCII)
        )
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
