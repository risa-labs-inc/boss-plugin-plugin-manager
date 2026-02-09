package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Tab types for the Plugin Manager panel.
 * Matching bundled plugin-panel-manager exactly.
 */
enum class PluginManagerTab {
    INSTALLED,
    AVAILABLE,
    UPDATES,
    PUBLISH
}

/**
 * State for the Plugin Manager panel.
 * Matching bundled plugin-panel-manager exactly.
 */
data class PluginManagerState(
    val currentTab: PluginManagerTab = PluginManagerTab.INSTALLED,
    val installedPlugins: List<InstalledPluginState> = emptyList(),
    val availablePlugins: List<PluginStoreItem> = emptyList(),
    val updates: List<UpdateInfo> = emptyList(),
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val error: String? = null,
    val isStoreAdmin: Boolean = false
)

/**
 * ViewModel for the Plugin Manager panel.
 * Matching bundled plugin-panel-manager exactly.
 */
class PluginManagerViewModel(
    private val scope: CoroutineScope,
    loaderDelegate: PluginLoaderDelegate?,
    private val onOpenUrl: ((String) -> Unit)? = null
) {
    private val apiImpl = PluginManagerAPIImpl(scope, loaderDelegate)
    private val api: PluginManagerAPI = apiImpl

    private val _state = MutableStateFlow(PluginManagerState())
    val state: StateFlow<PluginManagerState> = _state.asStateFlow()

    init {
        // Observe installed plugins from API and convert to InstalledPluginState
        scope.launch {
            api.observeInstalledPlugins().collect { plugins ->
                val installedStates = plugins.map { plugin ->
                    InstalledPluginState(
                        pluginId = plugin.pluginId,
                        displayName = plugin.displayName,
                        version = plugin.version,
                        description = plugin.description,
                        enabled = plugin.isEnabled,
                        healthy = true, // LoadedPluginInfo.healthy comes from PluginState.LOADED check
                        canUnload = plugin.canUnload,
                        jarPath = plugin.jarPath,
                        url = plugin.url.ifEmpty { null },
                        requiresAdmin = false
                    )
                }
                _state.value = _state.value.copy(installedPlugins = installedStates)
            }
        }

        // Initial refresh
        scope.launch {
            refresh()
        }
    }

    /**
     * Select a tab.
     */
    fun selectTab(tab: PluginManagerTab) {
        _state.value = _state.value.copy(currentTab = tab)
        // Auto-refresh store when switching to Available tab
        if (tab == PluginManagerTab.AVAILABLE && _state.value.availablePlugins.isEmpty()) {
            refreshStore()
        }
    }

    /**
     * Update search query.
     */
    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    /**
     * Refresh all data.
     */
    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                // Check for updates
                checkForUpdatesInternal()
                // Check admin status (matching bundled version behavior)
                val isAdmin = try {
                    apiImpl.isCurrentUserAdmin()
                } catch (e: Exception) {
                    false
                }
                _state.value = _state.value.copy(isLoading = false, isStoreAdmin = isAdmin)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Refresh failed"
                )
            }
        }
    }

    /**
     * Refresh store plugins.
     */
    private fun refreshStore() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = api.fetchStorePlugins()
            result.fold(
                onSuccess = { plugins ->
                    _state.value = _state.value.copy(
                        availablePlugins = plugins,
                        isLoading = false
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to fetch plugins"
                    )
                }
            )
        }
    }

    /**
     * Check for updates internally.
     */
    private suspend fun checkForUpdatesInternal() {
        try {
            val updateMap = api.checkForUpdates()
            val updateInfos = updateMap.map { (pluginId, newVersion) ->
                val installed = _state.value.installedPlugins.find { it.pluginId == pluginId }
                UpdateInfo(
                    pluginId = pluginId,
                    displayName = installed?.displayName ?: pluginId,
                    currentVersion = installed?.version ?: "",
                    newVersion = newVersion
                )
            }
            _state.value = _state.value.copy(updates = updateInfos)
        } catch (e: Exception) {
            // Silently fail update check
        }
    }

    /**
     * Install a plugin from the store.
     */
    fun installFromRemote(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = api.installPlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
                is InstallResult.AlreadyInstalled -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Plugin already installed (v${result.currentVersion})"
                    )
                }
                is InstallResult.DownloadFailed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Download failed: ${result.error}"
                    )
                }
                is InstallResult.LoadFailed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Load failed: ${result.error}"
                    )
                }
                is InstallResult.VersionConflict -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Version conflict: requires ${result.required}, available ${result.available}"
                    )
                }
            }
        }
    }

    /**
     * Install a plugin from a file picker.
     * Note: File picker is not available in dynamic plugin context,
     * but we include this for API compatibility with bundled plugin.
     */
    fun installFromFilePicker() {
        // File picker not available in dynamic plugin context
        _state.value = _state.value.copy(
            error = "File picker not available. Use GitHub URL instead."
        )
    }

    /**
     * Install a plugin from a GitHub URL.
     */
    fun installFromGitHub(githubUrl: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = api.installFromGitHub(githubUrl)
            when (result) {
                is InstallResult.Success -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
                is InstallResult.DownloadFailed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Download failed: ${result.error}"
                    )
                }
                is InstallResult.LoadFailed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Load failed: ${result.error}"
                    )
                }
                else -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    /**
     * Uninstall a plugin.
     */
    fun uninstallPlugin(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = api.uninstallPlugin(pluginId)
            when (result) {
                is UninstallResult.Success -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
                is UninstallResult.NotFound -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Plugin not found: ${result.pluginId}"
                    )
                }
                is UninstallResult.CannotUnload -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Cannot uninstall: ${result.reason}"
                    )
                }
                is UninstallResult.Failed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Uninstall failed: ${result.error}"
                    )
                }
            }
        }
    }

    /**
     * Update a single plugin.
     */
    fun updatePlugin(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = api.updatePlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    // Remove from updates list
                    val newUpdates = _state.value.updates.filter { it.pluginId != pluginId }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        updates = newUpdates
                    )
                }
                is InstallResult.DownloadFailed -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "Update failed: ${result.error}"
                    )
                }
                else -> {
                    _state.value = _state.value.copy(isLoading = false)
                }
            }
        }
    }

    /**
     * Update all plugins.
     */
    fun updateAllPlugins() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val updates = _state.value.updates.toList()
            val failures = mutableListOf<String>()

            for (update in updates) {
                val result = api.updatePlugin(update.pluginId)
                if (result is InstallResult.DownloadFailed || result is InstallResult.LoadFailed) {
                    failures.add(update.displayName)
                }
            }

            if (failures.isNotEmpty()) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to update: ${failures.joinToString(", ")}",
                    updates = emptyList()
                )
            } else {
                _state.value = _state.value.copy(
                    isLoading = false,
                    updates = emptyList()
                )
            }
        }
    }

    /**
     * Toggle plugin enabled state.
     */
    fun togglePluginEnabled(pluginId: String, enabled: Boolean) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)

            if (enabled) {
                api.enablePlugin(pluginId)
            } else {
                api.disablePlugin(pluginId)
            }

            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /**
     * Open a URL in the browser.
     */
    fun openUrl(url: String) {
        if (url.isNotBlank()) {
            onOpenUrl?.invoke(url)
        }
    }

    /**
     * Delete a plugin from the store (admin only).
     */
    fun deleteFromStore(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = api.deleteFromStore(pluginId)
            result.fold(
                onSuccess = {
                    // Refresh store to reflect deletion
                    val storeResult = api.fetchStorePlugins()
                    storeResult.fold(
                        onSuccess = { plugins ->
                            _state.value = _state.value.copy(
                                availablePlugins = plugins,
                                isLoading = false
                            )
                        },
                        onFailure = {
                            _state.value = _state.value.copy(isLoading = false)
                        }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to delete plugin"
                    )
                }
            )
        }
    }

    /**
     * Fetch plugin JAR from GitHub release for publishing.
     */
    fun fetchFromGitHubForPublish(
        url: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (jarPath: String, manifest: ExtractedManifest) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            api.fetchFromGitHubForPublish(url, onProgress, onStatus, onSuccess, onError)
        }
    }

    /**
     * Open file picker to select a plugin JAR file.
     */
    fun browseForPluginJar(onResult: (String?) -> Unit) {
        scope.launch {
            api.browseForPluginJar(onResult)
        }
    }

    /**
     * Extract manifest from a JAR file.
     */
    fun extractManifest(jarPath: String, onResult: (ExtractedManifest?) -> Unit) {
        scope.launch {
            api.extractManifest(jarPath, onResult)
        }
    }

    /**
     * Publish a plugin to the store.
     */
    fun publishPlugin(
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
        onProgress: (Float) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            api.publishPlugin(
                jarPath = jarPath,
                pluginId = pluginId,
                displayName = displayName,
                version = version,
                homepageUrl = homepageUrl,
                authorName = authorName,
                description = description,
                changelog = changelog,
                tags = tags,
                iconUrl = iconUrl,
                pluginType = pluginType,
                apiVersion = apiVersion,
                minBossVersion = minBossVersion,
                onProgress = onProgress,
                onSuccess = onSuccess,
                onError = onError
            )
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Dispose resources.
     */
    fun dispose() {
        // Cleanup if needed
    }

    /**
     * Expose the API for other components/plugins to use.
     */
    fun getAPI(): PluginManagerAPI = api
}
