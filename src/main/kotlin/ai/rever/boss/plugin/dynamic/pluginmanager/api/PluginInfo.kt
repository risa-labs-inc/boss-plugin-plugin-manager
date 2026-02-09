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
 */
@Serializable
data class PluginStoreItem(
    val id: String = "",
    @SerialName("plugin_id")
    val pluginId: String,
    @SerialName("display_name")
    val displayName: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    @SerialName("github_url")
    val githubUrl: String = "",
    @SerialName("download_url")
    val downloadUrl: String = "",
    val type: String = "panel",
    @SerialName("api_version")
    val apiVersion: String = "",
    @SerialName("min_boss_version")
    val minBossVersion: String = "",
    @SerialName("download_count")
    val downloadCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList()
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
