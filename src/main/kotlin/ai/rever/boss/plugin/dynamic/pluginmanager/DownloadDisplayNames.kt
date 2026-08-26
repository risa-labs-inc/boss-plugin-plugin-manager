package ai.rever.boss.plugin.dynamic.pluginmanager

import java.util.concurrent.ConcurrentHashMap

/**
 * Friendly names for transfers whose operation only knows a plugin id.
 *
 * The names are this plugin's to know: `installPlugin` takes an id, and only the
 * panel that listed the store row has the display name to go with it. Where the
 * progress itself goes - the host's download center, or this plugin's own widget
 * on a host without one - is `TransferReporter`'s business, not this file's.
 */
class DownloadDisplayNames {
    // A map rather than a flow: nothing observes these, and `remove` is the one
    // atomic operation the contract needs - read-then-update let two concurrent
    // takes both return the hint, which is not what "consumes" means.
    private val hints = ConcurrentHashMap<String, String>()

    /** Pre-seed a friendly display name for [key] before its operation starts. */
    fun hint(key: String, displayName: String) {
        if (displayName.isNotBlank()) hints[key] = displayName
    }

    /** The hinted name for [key], or [fallback]. Consumes the hint. */
    fun take(key: String, fallback: String): String = hints.remove(key) ?: fallback

    /** The hinted name for [key] without consuming it, or null. */
    fun peek(key: String): String? = hints[key]
}
