package ai.rever.boss.plugin.dynamic.pluginmanager.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Information about an installed plugin.
 */
@Serializable
data class PluginInfo(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val url: String = "",
    val type: String = "panel",
    val apiVersion: String = "",
    val minBossVersion: String = "",
    val isSystemPlugin: Boolean = false,
    val canUnload: Boolean = true,
    val loadPriority: Int = 100,
    val isEnabled: Boolean = true,
    val jarPath: String = "",
    val installedAt: Long = 0L
)

/**
 * Plugin available in the store.
 * Matches the API response format from /plugin-store/list
 */
@Serializable
data class PluginStoreItem(
    val id: String = "",
    val pluginId: String = "",
    val displayName: String = "",
    val version: String? = null,
    val latestVersion: String? = null,
    val description: String = "",
    val author: String = "",
    val url: String = "",
    val githubUrl: String = "",
    val downloadUrl: String = "",
    val type: String = "panel",
    val apiVersion: String = "",
    val minBossVersion: String = "",
    val downloadCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val verified: Boolean = false,
    val rating: Float = 0f,
    val ratingCount: Int = 0,
    val iconUrl: String = ""
)

/**
 * API response wrapper for plugin list.
 */
@Serializable
data class PluginListResponse(
    val plugins: List<PluginStoreItem> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * API response for download endpoint.
 * Contains signed URL and metadata for downloading plugin JAR.
 */
@Serializable
data class DownloadInfoResponse(
    val downloadUrl: String,
    val sha256: String = "",
    val version: String = "",
    val size: Long = 0,
    val versionId: String = ""
)

/**
 * Information about an available plugin update.
 */
@Serializable
data class UpdateInfo(
    val pluginId: String,
    val displayName: String,
    val currentVersion: String,
    val newVersion: String,
    val changelog: String = "",
    val critical: Boolean = false
)

/**
 * Result of a plugin installation attempt.
 */
sealed class InstallResult {
    data class Success(val plugin: PluginInfo) : InstallResult()
    data class AlreadyInstalled(val currentVersion: String) : InstallResult()
    data class DownloadFailed(val error: String) : InstallResult()
    data class LoadFailed(val error: String) : InstallResult()
    data class VersionConflict(val required: String, val available: String) : InstallResult()
}

/**
 * Result of a plugin uninstallation attempt.
 */
sealed class UninstallResult {
    data object Success : UninstallResult()
    data class NotFound(val pluginId: String) : UninstallResult()
    data class CannotUnload(val reason: String) : UninstallResult()
    data class Failed(val error: String) : UninstallResult()
}

/**
 * Installed plugin entry persisted in installed.json.
 */
@Serializable
data class InstalledPluginEntry(
    @SerialName("plugin_id")
    val pluginId: String,
    @SerialName("display_name")
    val displayName: String,
    val version: String,
    @SerialName("jar_path")
    val jarPath: String,
    @SerialName("installed_at")
    val installedAt: Long,
    @SerialName("github_url")
    val githubUrl: String = "",
    val enabled: Boolean = true
)

/**
 * Root structure for installed.json file.
 */
@Serializable
data class InstalledPluginsFile(
    val plugins: List<InstalledPluginEntry> = emptyList()
)

/**
 * Plugin type enum matching the Supabase schema.
 */
enum class PluginType(val value: String, val displayText: String) {
    PANEL("panel", "Panel (Sidebar)"),
    TAB("tab", "Tab (Main Area)"),
    HYBRID("hybrid", "Hybrid (Both)"),
    SERVICE("service", "Service");

    companion object {
        fun fromString(value: String): PluginType = entries.find { it.value == value } ?: PANEL
    }
}

/**
 * State for an installed plugin in the UI.
 * Matches bundled plugin-panel-manager exactly.
 */
data class InstalledPluginState(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val healthy: Boolean,
    val canUnload: Boolean,
    val jarPath: String,
    val url: String? = null,
    val requiresAdmin: Boolean = false
)

/**
 * Data extracted from a plugin's manifest.
 * Used when extracting manifest from JAR or fetching from GitHub.
 */
data class ExtractedManifest(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String,
    val author: String?,
    val url: String?,
    val apiVersion: String = "1.0",
    val minBossVersion: String = "",
    val type: PluginType = PluginType.PANEL
)

/**
 * Internal data class for parsing plugin.json manifest.
 */
@Serializable
internal data class PluginManifestData(
    @SerialName("pluginId")
    val pluginId: String,
    @SerialName("displayName")
    val displayName: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val url: String? = null,
    val type: String? = null,
    @SerialName("apiVersion")
    val apiVersion: String? = null,
    @SerialName("minBossVersion")
    val minBossVersion: String? = null
)
