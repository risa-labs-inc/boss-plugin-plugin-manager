package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What kind of operation a tracked download belongs to (drives the status-bar verb). */
enum class DownloadKind { INSTALL, UPDATE }

/**
 * A plugin download currently in flight.
 *
 * @param progress JAR download fraction in 0..1, or null while indeterminate
 *   (size unknown, or the operation is in a non-download phase such as
 *   fetching release info or loading the JAR).
 */
data class ActiveDownload(
    val key: String,
    val displayName: String,
    val kind: DownloadKind,
    val progress: Float? = null
)

/**
 * Tracks in-flight plugin downloads (install/update) so UI surfaces — the
 * bottom status-bar item — can show live progress. Owned by the shared
 * PluginManagerAPIImpl, so it covers panel-triggered installs and background
 * update prompts alike.
 */
class DownloadProgressTracker {
    private val _downloads = MutableStateFlow<Map<String, ActiveDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, ActiveDownload>> = _downloads.asStateFlow()

    /** Friendly names for keys whose operations only know the pluginId. */
    private val nameHints = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Pre-seed a friendly display name for [key] before its operation starts. */
    fun hintDisplayName(key: String, displayName: String) {
        if (displayName.isNotBlank()) nameHints.update { it + (key to displayName) }
    }

    /**
     * Begin tracking [key]. Returns true when this call created the entry —
     * nested operations on the same key return false and must not [end] it.
     */
    fun begin(key: String, displayName: String, kind: DownloadKind): Boolean {
        var created = false
        _downloads.update { m ->
            if (key in m) {
                created = false
                m
            } else {
                created = true
                m + (key to ActiveDownload(key, nameHints.value[key] ?: displayName, kind))
            }
        }
        return created
    }

    fun progress(key: String, fraction: Float) {
        _downloads.update { m ->
            val entry = m[key] ?: return@update m
            m + (key to entry.copy(progress = fraction.coerceIn(0f, 1f)))
        }
    }

    fun end(key: String) {
        _downloads.update { it - key }
        nameHints.update { it - key }
    }
}
