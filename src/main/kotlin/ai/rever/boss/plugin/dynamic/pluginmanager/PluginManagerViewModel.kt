package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel for the Plugin Manager panel.
 */
class PluginManagerViewModel(
    private val scope: CoroutineScope,
    loaderDelegate: PluginLoaderDelegate?
) {
    private val api: PluginManagerAPI = PluginManagerAPIImpl(scope, loaderDelegate)

    // UI State
    private val _selectedTab = MutableStateFlow(PluginManagerTab.INSTALLED)
    val selectedTab: StateFlow<PluginManagerTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _storePlugins = MutableStateFlow<List<PluginStoreItem>>(emptyList())
    val storePlugins: StateFlow<List<PluginStoreItem>> = _storePlugins.asStateFlow()

    private val _availableUpdates = MutableStateFlow<Map<String, String>>(emptyMap())
    val availableUpdates: StateFlow<Map<String, String>> = _availableUpdates.asStateFlow()

    // Installed plugins from API
    val installedPlugins: StateFlow<List<PluginInfo>> = api.observeInstalledPlugins()

    // Filtered installed plugins based on search
    val filteredInstalledPlugins: StateFlow<List<PluginInfo>> = combine(
        installedPlugins,
        searchQuery
    ) { plugins, query ->
        if (query.isBlank()) {
            plugins
        } else {
            plugins.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.pluginId.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    // Filtered store plugins based on search
    val filteredStorePlugins: StateFlow<List<PluginStoreItem>> = combine(
        storePlugins,
        searchQuery
    ) { plugins, query ->
        if (query.isBlank()) {
            plugins
        } else {
            plugins.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.pluginId.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
            }
        }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    init {
        // Listen for events
        scope.launch {
            api.observeEvents().collect { event ->
                when (event) {
                    is PluginEvent.PluginInstalled -> {
                        // Refresh store to update install status
                    }
                    is PluginEvent.PluginUninstalled -> {
                        // Refresh
                    }
                    else -> {}
                }
            }
        }
    }

    fun selectTab(tab: PluginManagerTab) {
        _selectedTab.value = tab
        if (tab == PluginManagerTab.STORE && _storePlugins.value.isEmpty()) {
            refreshStore()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refreshStore() {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = api.fetchStorePlugins()
            result.fold(
                onSuccess = { plugins ->
                    _storePlugins.value = plugins
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Failed to fetch plugins"
                }
            )

            _isLoading.value = false
        }
    }

    fun checkForUpdates() {
        scope.launch {
            _isLoading.value = true
            val updates = api.checkForUpdates()
            _availableUpdates.value = updates
            _isLoading.value = false
        }
    }

    fun installPlugin(pluginId: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = api.installPlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    // Success - UI will update automatically
                }
                is InstallResult.AlreadyInstalled -> {
                    _error.value = "Plugin already installed (v${result.currentVersion})"
                }
                is InstallResult.DownloadFailed -> {
                    _error.value = "Download failed: ${result.error}"
                }
                is InstallResult.LoadFailed -> {
                    _error.value = "Load failed: ${result.error}"
                }
                is InstallResult.VersionConflict -> {
                    _error.value = "Version conflict: requires ${result.required}, available ${result.available}"
                }
            }

            _isLoading.value = false
        }
    }

    fun installFromGitHub(githubUrl: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = api.installFromGitHub(githubUrl)
            when (result) {
                is InstallResult.Success -> {
                    // Success
                }
                is InstallResult.DownloadFailed -> {
                    _error.value = "Download failed: ${result.error}"
                }
                is InstallResult.LoadFailed -> {
                    _error.value = "Load failed: ${result.error}"
                }
                else -> {}
            }

            _isLoading.value = false
        }
    }

    fun uninstallPlugin(pluginId: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = api.uninstallPlugin(pluginId)
            when (result) {
                is UninstallResult.Success -> {
                    // Success
                }
                is UninstallResult.NotFound -> {
                    _error.value = "Plugin not found: ${result.pluginId}"
                }
                is UninstallResult.CannotUnload -> {
                    _error.value = "Cannot uninstall: ${result.reason}"
                }
                is UninstallResult.Failed -> {
                    _error.value = "Uninstall failed: ${result.error}"
                }
            }

            _isLoading.value = false
        }
    }

    fun updatePlugin(pluginId: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = api.updatePlugin(pluginId)
            when (result) {
                is InstallResult.Success -> {
                    _availableUpdates.value = _availableUpdates.value - pluginId
                }
                is InstallResult.DownloadFailed -> {
                    _error.value = "Update failed: ${result.error}"
                }
                else -> {}
            }

            _isLoading.value = false
        }
    }

    fun enablePlugin(pluginId: String) {
        scope.launch {
            api.enablePlugin(pluginId)
        }
    }

    fun disablePlugin(pluginId: String) {
        scope.launch {
            api.disablePlugin(pluginId)
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun dispose() {
        // Cleanup if needed
    }

    /**
     * Expose the API for other components/plugins to use.
     */
    fun getAPI(): PluginManagerAPI = api
}

enum class PluginManagerTab {
    INSTALLED,
    STORE
}
