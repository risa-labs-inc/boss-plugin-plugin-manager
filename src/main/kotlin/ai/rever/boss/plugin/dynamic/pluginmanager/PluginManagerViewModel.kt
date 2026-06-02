package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import ai.rever.boss.plugin.dynamic.pluginmanager.realtime.StoreChangeEvent
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
    /** Per-plugin loading state — tracks which plugins are currently being installed/updated/uninstalled. */
    val busyPlugins: Set<String> = emptySet(),
    val searchQuery: String = "",
    val error: String? = null,
    val isStoreAdmin: Boolean = false,
    val realtimeConnected: Boolean = false,
    /** Open version-history / downgrade sheet, or null when closed. */
    val versionSheet: VersionSheetState? = null,
    /** Prompt shown after an update to reset running instances / restart BOSS, or null. */
    val postUpdatePrompt: PostUpdatePrompt? = null
)

/**
 * Prompt shown after a successful update so the new version actually takes effect.
 * Either offers to reset the plugin's running instances (close their tabs), or — for
 * plugins that only apply on a full restart (system/locked / JAR-swap) — to restart BOSS.
 */
data class PostUpdatePrompt(
    /** Plugins to reset (closed) when [needsRestart] is false. */
    val pluginIds: List<String>,
    /** "<name>" for a single plugin, or "N plugins" for update-all. */
    val displayName: String,
    /** True → offer "Restart BOSS"; false → offer to reset [instanceCount] running instances. */
    val needsRestart: Boolean,
    /** Total open instances that would be reset (only meaningful when !needsRestart). */
    val instanceCount: Int
)

/**
 * State for the per-plugin version-history sheet (Update / Downgrade + IPC
 * compatibility badges).
 */
data class VersionSheetState(
    val pluginId: String,
    val displayName: String,
    val installedVersion: String,
    val isLoading: Boolean = true,
    val versions: List<PluginVersionInfo> = emptyList(),
    val hostIpcVersion: String? = IpcCompat.hostVersion,
    val error: String? = null
)

/**
 * ViewModel for the Plugin Manager panel.
 * Matching bundled plugin-panel-manager exactly.
 */
@OptIn(FlowPreview::class)
class PluginManagerViewModel(
    private val scope: CoroutineScope,
    private val loaderDelegate: PluginLoaderDelegate?,
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
                        requiresAdmin = false,
                        isIncompatible = plugin.isIncompatible
                    )
                }
                _state.value = _state.value.copy(installedPlugins = installedStates)
            }
        }

        // Collect realtime store changes and auto-refresh (debounced to avoid thundering herd)
        scope.launch {
            apiImpl.storeChanges
                .debounce(500)
                .collect { event ->
                    when (event) {
                        is StoreChangeEvent.PluginChanged -> refreshStoreInternal()
                        is StoreChangeEvent.VersionAdded -> checkForUpdatesInternal()
                    }
                }
        }

        // Track realtime connection status
        scope.launch {
            apiImpl.realtimeClient.isConnected.collect { connected ->
                _state.value = _state.value.copy(realtimeConnected = connected)
            }
        }

        // Connect realtime
        apiImpl.connectRealtime()

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
        if (tab == PluginManagerTab.AVAILABLE) {
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
                // Refresh installed plugins first (needed by checkForUpdates)
                apiImpl.refreshInstalledPlugins()
                // Run store fetch, update check, and admin check in parallel
                coroutineScope {
                    launch { refreshStoreInternal() }
                    launch { checkForUpdatesInternal() }
                    launch {
                        val isAdmin = try {
                            apiImpl.isCurrentUserAdmin()
                        } catch (e: Exception) {
                            false
                        }
                        _state.value = _state.value.copy(isStoreAdmin = isAdmin)
                    }
                }
                _state.value = _state.value.copy(isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Refresh failed"
                )
            }
        }
    }

    /**
     * Refresh store plugins (with loading indicator).
     */
    private fun refreshStore() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            refreshStoreInternal()
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /**
     * Refresh store plugins internally (without changing loading state).
     */
    private suspend fun refreshStoreInternal() {
        val result = api.fetchStorePlugins()
        result.fold(
            onSuccess = { plugins ->
                _state.value = _state.value.copy(availablePlugins = plugins)
            },
            onFailure = { e ->
                _state.value = _state.value.copy(
                    error = e.message ?: "Failed to fetch plugins"
                )
            }
        )
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
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)

            val result = api.installPlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    apiImpl.refreshInstalledPlugins()
                    refreshStoreInternal()
                    _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
                }
                is InstallResult.AlreadyInstalled -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Plugin already installed (v${result.currentVersion})"
                    )
                }
                is InstallResult.DownloadFailed -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Download failed: ${result.error}"
                    )
                }
                is InstallResult.LoadFailed -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Load failed: ${result.error}"
                    )
                }
                is InstallResult.VersionConflict -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
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
                    // Refresh installed plugins after successful install
                    apiImpl.refreshInstalledPlugins()
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
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)

            val result = api.uninstallPlugin(pluginId)
            when (result) {
                is UninstallResult.Success -> {
                    _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
                }
                is UninstallResult.NotFound -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Plugin not found: ${result.pluginId}"
                    )
                }
                is UninstallResult.CannotUnload -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Cannot uninstall: ${result.reason}"
                    )
                }
                is UninstallResult.Failed -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
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
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)

            val result = api.updatePlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    val newUpdates = _state.value.updates.filter { it.pluginId != pluginId }
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        updates = newUpdates,
                        postUpdatePrompt = buildPostUpdatePrompt(listOf(pluginId))
                    )
                }
                is InstallResult.DownloadFailed -> {
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
                        error = "Update failed: ${result.error}"
                    )
                }
                else -> {
                    _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
                }
            }
        }
    }

    /**
     * Open the version-history / downgrade sheet for a plugin and load its
     * published versions (each tagged with IPC compatibility).
     */
    fun openVersions(pluginId: String, displayName: String, installedVersion: String) {
        _state.value = _state.value.copy(
            versionSheet = VersionSheetState(
                pluginId = pluginId,
                displayName = displayName,
                installedVersion = installedVersion
            )
        )
        scope.launch {
            val result = api.fetchPluginVersions(pluginId)
            val sheet = _state.value.versionSheet ?: return@launch
            if (sheet.pluginId != pluginId) return@launch // sheet changed while loading
            _state.value = _state.value.copy(
                versionSheet = result.fold(
                    onSuccess = { sheet.copy(isLoading = false, versions = it) },
                    onFailure = { sheet.copy(isLoading = false, error = it.message ?: "Failed to load versions") }
                )
            )
        }
    }

    fun closeVersions() {
        _state.value = _state.value.copy(versionSheet = null)
    }

    /**
     * Install (downgrade/upgrade to) a specific version. Incompatible versions
     * are gated in the API; the UI also disables their buttons.
     */
    fun installVersion(pluginId: String, version: String) {
        scope.launch {
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)
            val result = api.installVersion(pluginId, version)
            val base = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
            _state.value = when (result) {
                is InstallResult.Success -> base.copy(versionSheet = null)
                is InstallResult.DownloadFailed -> base.copy(error = "Install failed: ${result.error}")
                is InstallResult.LoadFailed -> base.copy(error = "Install failed: ${result.error}")
                else -> base
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
            val succeeded = mutableListOf<String>()

            for (update in updates) {
                val result = api.updatePlugin(update.pluginId)
                if (result is InstallResult.DownloadFailed || result is InstallResult.LoadFailed) {
                    failures.add(update.displayName)
                } else if (result is InstallResult.Success) {
                    succeeded.add(update.pluginId)
                }
            }

            _state.value = _state.value.copy(
                isLoading = false,
                error = if (failures.isNotEmpty()) "Failed to update: ${failures.joinToString(", ")}" else null,
                updates = emptyList(),
                postUpdatePrompt = buildPostUpdatePrompt(succeeded)
            )
        }
    }

    /**
     * Build the post-update prompt for the given just-updated plugins, or null if
     * nothing needs resetting. Plugins that only apply on a full restart
     * (system/locked) take priority and produce a "Restart BOSS" prompt; otherwise
     * we prompt to reset the ones that currently have running instances.
     */
    private fun buildPostUpdatePrompt(pluginIds: List<String>): PostUpdatePrompt? {
        val delegate = loaderDelegate ?: return null
        if (pluginIds.isEmpty()) return null
        val loaded = delegate.getLoadedPlugins().associateBy { it.pluginId }

        val restartNeeded = pluginIds.filter { id ->
            loaded[id]?.let { it.isSystemPlugin || !it.canUnload } ?: false
        }
        if (restartNeeded.isNotEmpty()) {
            return PostUpdatePrompt(
                pluginIds = restartNeeded,
                displayName = displayNameFor(restartNeeded, loaded),
                needsRestart = true,
                instanceCount = 0
            )
        }

        val withInstances = pluginIds.filter { delegate.getRunningInstanceCount(it) > 0 }
        if (withInstances.isEmpty()) return null
        val total = withInstances.sumOf { delegate.getRunningInstanceCount(it) }
        return PostUpdatePrompt(
            pluginIds = withInstances,
            displayName = displayNameFor(withInstances, loaded),
            needsRestart = false,
            instanceCount = total
        )
    }

    private fun displayNameFor(ids: List<String>, loaded: Map<String, LoadedPluginInfo>): String =
        if (ids.size == 1) (loaded[ids[0]]?.displayName ?: ids[0]) else "${ids.size} plugins"

    /** Confirm the post-update prompt: reset the affected plugins' running instances. */
    fun confirmResetInstances() {
        val prompt = _state.value.postUpdatePrompt ?: return
        _state.value = _state.value.copy(postUpdatePrompt = null)
        scope.launch {
            prompt.pluginIds.forEach { id ->
                runCatching { loaderDelegate?.resetPluginInstances(id) }
            }
        }
    }

    /** Confirm the post-update prompt: restart the BOSS application. */
    fun confirmRestartApplication() {
        _state.value = _state.value.copy(postUpdatePrompt = null)
        loaderDelegate?.restartApplication()
    }

    /** Dismiss the post-update prompt without resetting/restarting. */
    fun dismissPostUpdatePrompt() {
        _state.value = _state.value.copy(postUpdatePrompt = null)
    }

    /**
     * Toggle plugin enabled state.
     */
    fun togglePluginEnabled(pluginId: String, enabled: Boolean) {
        scope.launch {
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)

            if (enabled) {
                api.enablePlugin(pluginId)
            } else {
                api.disablePlugin(pluginId)
            }

            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
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
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)
            val result = api.deleteFromStore(pluginId)
            result.fold(
                onSuccess = {
                    val storeResult = api.fetchStorePlugins()
                    storeResult.fold(
                        onSuccess = { plugins ->
                            _state.value = _state.value.copy(
                                availablePlugins = plugins,
                                busyPlugins = _state.value.busyPlugins - pluginId
                            )
                        },
                        onFailure = {
                            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins - pluginId)
                        }
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        busyPlugins = _state.value.busyPlugins - pluginId,
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
        apiImpl.realtimeClient.dispose()
    }

    /**
     * Expose the API for other components/plugins to use.
     */
    fun getAPI(): PluginManagerAPI = api
}
