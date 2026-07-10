package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.api.McpServerController
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
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
    MCP,
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
    /** Whether the user may use the Publish tab (store admin OR has plugins.admin.publish). */
    val canPublish: Boolean = false,
    /** Installed plugins hidden from this (non-admin) user for lack of permissions. */
    val inaccessiblePlugins: List<InaccessiblePluginInfo> = emptyList(),
    val realtimeConnected: Boolean = false,
    /** Open version-history / downgrade sheet, or null when closed. */
    val versionSheet: VersionSheetState? = null,
    /** Prompt shown after an update to reset running instances / restart BOSS, or null. */
    val postUpdatePrompt: PostUpdatePrompt? = null,
    /**
     * RBAC permission glossary: name → description, fetched best-effort from
     * RoleManagementProvider (empty when unavailable, e.g. non-admin users).
     * Used by the MCP dialog to explain what each required permission grants.
     */
    val permissionDescriptions: Map<String, String> = emptyMap()
)

/**
 * Prompt shown after a successful update so the new version actually takes effect.
 * Offers to reset the plugin's running instances (close their tabs), to hot-swap the
 * API layer (API plugin update — reloads every plugin), or — for updates only a full
 * restart can apply — to restart BOSS.
 */
data class PostUpdatePrompt(
    /** Plugins the confirm action applies to. */
    val pluginIds: List<String>,
    /** "<name>" for a single plugin, or "N plugins" for update-all. */
    val displayName: String,
    /** Which apply action to offer. */
    val kind: Kind,
    /** Total open instances that would be reset (only meaningful for [Kind.RESET]). */
    val instanceCount: Int = 0,
    /** On-disk JAR of the updated API plugin (only set for [Kind.API_SWAP]). */
    val apiJarPath: String? = null
) {
    enum class Kind { RESTART, RESET, API_SWAP }
}

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
 *
 * Uses the shared [PluginManagerCore] instances (API impl + realtime client);
 * the core owns their lifecycle, so panel close only cancels this ViewModel's
 * own collectors.
 */
@OptIn(FlowPreview::class)
class PluginManagerViewModel(
    parentScope: CoroutineScope,
    core: PluginManagerCore,
    private val onOpenUrl: ((String) -> Unit)? = null,
    /** Registry of plugin-contributed MCP tools, for the MCP tab. Null if unavailable. */
    val mcpToolRegistry: McpToolRegistry? = null,
    /**
     * Lazy resolver for the MCP server control surface (terminal-tab's plugin
     * API). Resolved per-render, NOT at construction: plugin-manager loads
     * before terminal-tab (loadPriority 5 vs 10), so getPluginAPI is null until
     * terminal-tab registers.
     */
    val mcpServerControllerProvider: () -> McpServerController? = { null },
    /** Role management provider for the permission-description glossary; null for non-admins. */
    private val roleManagementProvider: RoleManagementProvider? = null,
    /** Reveals a sidebar panel by id (host >= 9.2.33); used to launch Tool Creator from the Create tab. */
    private val panelEventProvider: PanelEventProvider? = null,
    /** Event bus for signaling Tool Creator to open its New Tool dialog. */
    private val applicationEventBus: ApplicationEventBus? = null,
    /** This window's id, needed to target panel-open events. */
    private val windowId: String? = null
) {
    // Child scope of the plugin scope: cancelled in dispose() so collectors of a
    // closed panel don't leak, while plugin unload still cancels everything.
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )

    private val apiImpl = core.apiImpl
    private val api: PluginManagerAPI = core.api
    private val loaderDelegate = core.loaderDelegate

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

        // Track realtime connection status (connection itself is owned by PluginManagerCore)
        scope.launch {
            apiImpl.realtimeClient.isConnected.collect { connected ->
                _state.value = _state.value.copy(realtimeConnected = connected)
            }
        }

        // Initial refresh
        scope.launch {
            refresh()
        }

        // Best-effort permission glossary (name → description) for the MCP
        // dialog. RoleManagementProvider is null for non-admins; leave empty.
        scope.launch {
            runCatching {
                roleManagementProvider?.getAllPermissions()?.getOrNull()?.let { perms ->
                    _state.value = _state.value.copy(
                        permissionDescriptions = perms.associate { it.name to (it.description ?: "") }
                    )
                }
            }
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
                        val canPublish = try {
                            apiImpl.canPublish()
                        } catch (e: Exception) {
                            isAdmin
                        }
                        // Plugins installed but hidden from this user for lack of permissions.
                        val inaccessible = try {
                            loaderDelegate?.getInaccessiblePlugins() ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                        _state.value = _state.value.copy(
                            isStoreAdmin = isAdmin,
                            canPublish = canPublish,
                            inaccessiblePlugins = inaccessible
                        )
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
    /**
     * Whether the current user may install [item] from the store. Mirrors the
     * server-side /download gate (admins bypass; otherwise the JWT must carry the
     * plugin's requiredPermissions; empty = open). Used to gate the Install button.
     */
    fun canInstall(item: PluginStoreItem): Boolean = apiImpl.canInstall(item.requiredPermissions)

    /** Whether the Tool Creator plugin is currently installed. */
    fun isToolCreatorInstalled(): Boolean =
        _state.value.installedPlugins.any { it.pluginId == TOOL_CREATOR_PLUGIN_ID }

    /**
     * Launch Tool Creator's New Tool flow: signal it to open its dialog, then
     * reveal its sidebar panel. Falls back to installing it from the store if
     * it isn't installed yet.
     */
    fun openToolCreator() {
        if (!isToolCreatorInstalled()) {
            installFromRemote(TOOL_CREATOR_PLUGIN_ID)
            return
        }
        scope.launch {
            applicationEventBus?.publish(
                CustomPluginEvent(
                    sourcePluginId = PluginManagerCore.PLUGIN_ID,
                    eventName = TOOL_CREATOR_OPEN_EVENT,
                )
            )
            val wid = windowId
            if (panelEventProvider != null && wid != null) {
                panelEventProvider.openPanel(
                    PanelId(panelId = TOOL_CREATOR_PANEL_ID, defaultOrder = 0, pluginId = "ai.rever.boss"),
                    wid,
                )
            }
        }
    }

    fun installFromRemote(pluginId: String) {
        scope.launch {
            _state.value = _state.value.copy(busyPlugins = _state.value.busyPlugins + pluginId, error = null)

            // Friendly name for the status-bar progress item (the API only gets the id).
            _state.value.availablePlugins.find { it.pluginId == pluginId }?.displayName
                ?.let { apiImpl.downloadTracker.hintDisplayName(pluginId, it) }

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
                is InstallResult.Success -> base.copy(
                    versionSheet = null,
                    // Version changes of system/locked plugins land on disk only;
                    // hot-reload (or prompt) so the chosen version actually runs.
                    postUpdatePrompt = buildPostUpdatePrompt(listOf(pluginId))
                )
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
     * Apply what can be applied and build the post-update prompt for the given
     * just-updated plugins, or null if nothing further is needed. Decision
     * logic is shared with the background update toast flow via
     * [buildUpdateApplyPlan]. Hot-reloadable system/locked plugins are
     * reloaded here directly (no prompt); only a failed reload falls back to
     * the restart prompt.
     */
    private suspend fun buildPostUpdatePrompt(pluginIds: List<String>): PostUpdatePrompt? {
        return when (val plan = buildUpdateApplyPlan(pluginIds, loaderDelegate)) {
            is UpdateApplyPlan.Reload -> {
                val failed = plan.pluginIds.filter { id ->
                    runCatching { loaderDelegate?.reloadPlugin(id) }.getOrNull() == null
                }
                if (failed.isEmpty()) null
                else PostUpdatePrompt(
                    pluginIds = failed,
                    displayName = plan.displayName,
                    kind = PostUpdatePrompt.Kind.RESTART
                )
            }
            is UpdateApplyPlan.SwapApiLayer -> PostUpdatePrompt(
                pluginIds = pluginIds,
                displayName = plan.displayName,
                kind = PostUpdatePrompt.Kind.API_SWAP,
                apiJarPath = plan.jarPath
            )
            is UpdateApplyPlan.Restart -> PostUpdatePrompt(
                pluginIds = plan.pluginIds,
                displayName = plan.displayName,
                kind = PostUpdatePrompt.Kind.RESTART
            )
            is UpdateApplyPlan.Reset -> PostUpdatePrompt(
                pluginIds = plan.pluginIds,
                displayName = plan.displayName,
                kind = PostUpdatePrompt.Kind.RESET,
                instanceCount = plan.instanceCount
            )
            is UpdateApplyPlan.None -> null
        }
    }

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

    /** Confirm the post-update prompt: hot-swap the API layer (reloads every plugin). */
    fun confirmApiSwap() {
        val prompt = _state.value.postUpdatePrompt ?: return
        _state.value = _state.value.copy(postUpdatePrompt = null)
        val jarPath = prompt.apiJarPath ?: return
        scope.launch {
            // Loading the newer api jar triggers the host's detached API-layer
            // swap, which unloads THIS plugin mid-flight — the reloaded Toolbox
            // comes back fresh, so there is no state to update here.
            runCatching { loaderDelegate?.loadPlugin(jarPath) }
        }
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
     * Dispose resources. Cancels this ViewModel's collectors only — the shared
     * API impl and realtime client are owned by [PluginManagerCore] and keep
     * running so background update detection survives panel close.
     */
    fun dispose() {
        scope.cancel()
    }

    companion object {
        const val TOOL_CREATOR_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.toolcreator"
        const val TOOL_CREATOR_PANEL_ID = "tool-creator"
        /** Must match ToolCreatorDynamicPlugin.OPEN_NEW_TOOL_EVENT. */
        const val TOOL_CREATOR_OPEN_EVENT = "open-new-tool"
    }
}
