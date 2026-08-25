package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.TransferKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Friendly names for transfers whose operation only knows a plugin id.
 *
 * What is left of the tracker this plugin used to own. Progress itself now goes
 * to the host's download center (`DownloadCenterProvider`), which renders one
 * bottom-bar item for every transfer in the app rather than one this plugin drew
 * for its own - the reason a download the HOST started used to show nothing.
 *
 * The names stay here because they are this plugin's to know: `installPlugin`
 * takes an id, and only the panel that listed the store row has the display name
 * to go with it.
 */
class DownloadDisplayNames {
    private val hints = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Pre-seed a friendly display name for [key] before its operation starts. */
    fun hint(key: String, displayName: String) {
        if (displayName.isNotBlank()) hints.update { it + (key to displayName) }
    }

    /** The hinted name for [key], or [fallback]. Consumes the hint. */
    fun take(key: String, fallback: String): String {
        val hinted = hints.value[key]
        if (hinted != null) hints.update { it - key }
        return hinted ?: fallback
    }
}

/** Maps this plugin's two operations onto the host's transfer kinds. */
internal fun transferKindFor(isUpdate: Boolean): TransferKind =
    if (isUpdate) TransferKind.PLUGIN_UPDATE else TransferKind.PLUGIN_INSTALL
