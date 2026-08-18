package ai.rever.boss.plugin.dynamic.pluginmanager.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plugin Manager API - Self-contained API for managing BOSS plugins.
 *
 * This API is defined and implemented within the plugin-manager plugin itself,
 * minimizing dependencies on boss-plugin-api. The only external dependency
 * is PluginLoaderDelegate which BossConsole implements to provide
 * load/unload capabilities.
 *
 * Other plugins can access this API via:
 * ```kotlin
 * val pluginManager = context.getPluginAPI(PluginManagerAPI::class.java)
 * pluginManager?.getInstalledPlugins()
 * ```
 */
interface PluginManagerAPI {

    // ========================================
    // INSTALLED PLUGINS
    // ========================================

    /**
     * Get list of all installed plugins.
     */
    fun getInstalledPlugins(): List<PluginInfo>

    /**
     * Get flow of installed plugins for reactive updates.
     */
    fun observeInstalledPlugins(): StateFlow<List<PluginInfo>>

    /**
     * Check if a specific plugin is installed.
     */
    fun isPluginInstalled(pluginId: String): Boolean

    /**
     * Get info about a specific installed plugin.
     */
    fun getInstalledPlugin(pluginId: String): PluginInfo?

    // ========================================
    // PLUGIN STORE
    // ========================================

    /**
     * Fetch available plugins from the store.
     *
     * @param query Optional search query
     * @param category Optional category filter
     * @return List of plugins available in the store
     */
    suspend fun fetchStorePlugins(
        query: String? = null,
        category: String? = null
    ): Result<List<PluginStoreItem>>

    /**
     * Get details of a specific plugin from the store.
     */
    suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem>

    /**
     * Fetch the full published version history of a plugin (newest first),
     * each tagged with its host-IPC compatibility. Powers the version /
     * downgrade UI.
     */
    suspend fun fetchPluginVersions(pluginId: String): Result<List<PluginVersionInfo>>

    /**
     * Install (or downgrade/upgrade to) a specific published version. Refuses
     * versions the host can't load on IPC grounds. For system/locked plugins
     * the new JAR replaces the old on disk and applies after restart; regular
     * plugins are unloaded and reloaded live.
     */
    suspend fun installVersion(pluginId: String, version: String): InstallResult

    /**
     * Check for updates for installed plugins.
     *
     * @return Map of pluginId to available version (only includes plugins with updates)
     */
    suspend fun checkForUpdates(): Map<String, String>

    /**
     * Like [checkForUpdates], but only returns updates whose latest published
     * version is installable on this host's IPC contract (COMPATIBLE or
     * UNKNOWN per [IpcCompat]). Versions requiring a newer host
     * (REQUIRES_HOST_UPDATE) or a different IPC major (MAJOR_MISMATCH) are
     * excluded. Returns an empty list if the compatibility lookup fails.
     */
    suspend fun checkForCompatibleUpdates(): List<UpdateInfo>

    // ========================================
    // INSTALL / UNINSTALL
    // ========================================

    /**
     * Install a plugin from the store.
     *
     * @param pluginId The plugin ID to install
     * @return Result of the installation
     */
    suspend fun installPlugin(pluginId: String): InstallResult

    /**
     * Install a plugin from a GitHub URL.
     *
     * @param githubUrl The GitHub repository URL
     * @return Result of the installation
     */
    suspend fun installFromGitHub(githubUrl: String): InstallResult

    /**
     * Install a plugin from a local JAR file.
     *
     * @param jarPath Path to the local JAR file
     * @return Result of the installation
     */
    suspend fun installFromFile(jarPath: String): InstallResult

    /**
     * Uninstall a plugin.
     *
     * @param pluginId The plugin ID to uninstall
     * @return Result of the uninstallation
     */
    suspend fun uninstallPlugin(pluginId: String): UninstallResult

    /**
     * Update a plugin to the latest version.
     *
     * @param pluginId The plugin ID to update
     * @return Result of the update (uses InstallResult)
     */
    suspend fun updatePlugin(pluginId: String): InstallResult

    // ========================================
    // ENABLE / DISABLE
    // ========================================

    /**
     * Enable a disabled plugin.
     */
    suspend fun enablePlugin(pluginId: String): Boolean

    /**
     * Disable a plugin without uninstalling it.
     */
    suspend fun disablePlugin(pluginId: String): Boolean

    // ========================================
    // EVENTS
    // ========================================

    /**
     * Observe plugin events (install, uninstall, update, etc.)
     */
    fun observeEvents(): Flow<PluginEvent>

    // ========================================
    // STORE ADMIN (PUBLISH)
    // ========================================

    /**
     * Delete a plugin from the store (admin only).
     */
    suspend fun deleteFromStore(pluginId: String): Result<Unit>

    /**
     * Fetch plugin JAR from GitHub release for publishing.
     */
    suspend fun fetchFromGitHubForPublish(
        url: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (jarPath: String, manifest: ExtractedManifest) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * Open file picker to select a plugin JAR file.
     */
    suspend fun browseForPluginJar(onResult: (String?) -> Unit)

    /**
     * Extract manifest from a JAR file.
     */
    suspend fun extractManifest(jarPath: String, onResult: (ExtractedManifest?) -> Unit)

    /**
     * Publish a plugin to the store.
     */
    suspend fun publishPlugin(
        jarPath: String,
        pluginId: String,
        displayName: String,
        version: String,
        homepageUrl: String,
        authorName: String,
        description: String?,
        changelog: String?,
        tags: List<String>,
        iconUrl: String?,
        pluginType: String,
        apiVersion: String,
        minBossVersion: String,
        /**
         * Organisation to publish under, or null to let the server derive it.
         *
         * Authorised server-side against the caller's publishing rights, so a value here is a
         * request rather than an instruction - naming one you may not publish for is a 403.
         */
        orgId: String?,
        onProgress: (Float) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    )
}

/**
 * Events emitted by the plugin manager.
 */
sealed class PluginEvent {
    data class PluginInstalled(val plugin: PluginInfo) : PluginEvent()
    data class PluginUninstalled(val pluginId: String) : PluginEvent()
    data class PluginUpdated(val plugin: PluginInfo, val previousVersion: String) : PluginEvent()
    data class PluginEnabled(val pluginId: String) : PluginEvent()
    data class PluginDisabled(val pluginId: String) : PluginEvent()
    data class PluginLoadFailed(val pluginId: String, val error: String) : PluginEvent()
    data class StoreRefreshed(val count: Int) : PluginEvent()
}
