package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.ExtractedManifest
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstalledPluginState
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginType
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import ai.rever.boss.plugin.ui.BossBadge
import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossSection
import ai.rever.boss.plugin.ui.BossTextArea
import ai.rever.boss.plugin.ui.BossTextField
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.boss.plugin.ui.BossToggle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Confirmation dialog for destructive actions.
 */
@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        BossCard(
            modifier = Modifier.width(320.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = title,
                    color = BossThemeColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossSecondaryButton(
                        text = "Cancel",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(
                        text = confirmText,
                        onClick = {
                            onConfirm()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Plugin Manager View - matching bundled plugin-panel-manager exactly.
 * Uses BossTheme and UI components from plugin-ui-core.
 */
@Composable
fun PluginManagerView(viewModel: PluginManagerViewModel) {
    val state by viewModel.state.collectAsState()

    BossTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BossThemeColors.BackgroundColor)
        ) {
            // Header with tabs and search
            PluginManagerHeader(
                currentTab = state.currentTab,
                updateCount = state.updates.size,
                searchQuery = state.searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onTabSelected = { viewModel.selectTab(it) },
                onRefresh = { viewModel.refresh() },
                isLoading = state.isLoading,
                isStoreAdmin = state.isStoreAdmin
            )

            // Error message
            if (state.error != null) {
                ErrorBanner(
                    message = state.error!!,
                    onDismiss = { viewModel.clearError() }
                )
            }

            // Content based on selected tab
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                when (state.currentTab) {
                    PluginManagerTab.INSTALLED -> InstalledPluginsTab(
                        plugins = filterPlugins(state.installedPlugins, state.searchQuery),
                        updateIds = state.updates.map { it.pluginId }.toSet(),
                        onToggleEnabled = { id, enabled -> viewModel.togglePluginEnabled(id, enabled) },
                        onUninstall = { id -> viewModel.uninstallPlugin(id) },
                        onUpdate = { id -> viewModel.updatePlugin(id) },
                        onInstallFromFile = { viewModel.installFromFilePicker() },
                        onInstallFromGitHub = { url -> viewModel.installFromGitHub(url) },
                        onOpenHomepage = { url -> viewModel.openUrl(url) },
                        isLoading = state.isLoading
                    )
                    PluginManagerTab.AVAILABLE -> AvailablePluginsTab(
                        plugins = filterAvailablePlugins(state.availablePlugins, state.searchQuery),
                        installedIds = state.installedPlugins.map { it.pluginId }.toSet(),
                        updateIds = state.updates.map { it.pluginId }.toSet(),
                        onInstall = { pluginId -> viewModel.installFromRemote(pluginId) },
                        onUpdate = { pluginId -> viewModel.updatePlugin(pluginId) },
                        onDeleteFromStore = { pluginId -> viewModel.deleteFromStore(pluginId) },
                        onOpenHomepage = { url -> viewModel.openUrl(url) },
                        isStoreAdmin = state.isStoreAdmin,
                        isLoading = state.isLoading
                    )
                    PluginManagerTab.UPDATES -> UpdatesTab(
                        updates = state.updates,
                        onUpdate = { id -> viewModel.updatePlugin(id) },
                        onUpdateAll = { viewModel.updateAllPlugins() },
                        isLoading = state.isLoading
                    )
                    PluginManagerTab.PUBLISH -> PublishTab(
                        onFetchFromGitHub = { url, onProgress, onStatus, onSuccess, onError ->
                            viewModel.fetchFromGitHubForPublish(url, onProgress, onStatus, onSuccess, onError)
                        },
                        onBrowseJar = { onResult -> viewModel.browseForPluginJar(onResult) },
                        onExtractManifest = { jarPath, onResult -> viewModel.extractManifest(jarPath, onResult) },
                        onPublish = { jarPath, pluginId, displayName, version, homepageUrl, authorName, description, changelog, tags, iconUrl, pluginType, apiVersion, minBossVersion, onProgress, onSuccess, onError ->
                            viewModel.publishPlugin(
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
                        },
                        isLoading = state.isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginManagerHeader(
    currentTab: PluginManagerTab,
    updateCount: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onTabSelected: (PluginManagerTab) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
    isStoreAdmin: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.SurfaceColor)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabButton(
            text = "Installed",
            selected = currentTab == PluginManagerTab.INSTALLED,
            onClick = { onTabSelected(PluginManagerTab.INSTALLED) }
        )
        TabButton(
            text = "Store",
            selected = currentTab == PluginManagerTab.AVAILABLE,
            onClick = { onTabSelected(PluginManagerTab.AVAILABLE) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TabButton(
                text = "Updates",
                selected = currentTab == PluginManagerTab.UPDATES,
                onClick = { onTabSelected(PluginManagerTab.UPDATES) }
            )
            if (updateCount > 0) {
                Spacer(Modifier.width(2.dp))
                BossBadge(count = updateCount)
            }
        }
        // Only show Publish tab for store admins
        if (isStoreAdmin) {
            TabButton(
                text = "Publish",
                selected = currentTab == PluginManagerTab.PUBLISH,
                onClick = { onTabSelected(PluginManagerTab.PUBLISH) }
            )
        }

        Spacer(Modifier.width(8.dp))

        // Search bar
        BossSearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = "Search...",
            modifier = Modifier.weight(1f)
        )

        Spacer(Modifier.width(4.dp))

        // Refresh button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !isLoading) { onRefresh() }
                .padding(4.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = BossThemeColors.AccentColor,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = BossThemeColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                else BossThemeColors.BackgroundColor
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = if (selected) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BossThemeColors.ErrorColor.copy(alpha = 0.15f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                color = BossThemeColors.ErrorColor,
                fontSize = 13.sp
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onDismiss() }
                .padding(4.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = BossThemeColors.ErrorColor,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun InstalledPluginsTab(
    plugins: List<InstalledPluginState>,
    updateIds: Set<String>,
    onToggleEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onInstallFromFile: () -> Unit,
    onInstallFromGitHub: (String) -> Unit,
    onOpenHomepage: (String) -> Unit,
    isLoading: Boolean
) {
    var showGitHubDialog by remember { mutableStateOf(false) }
    var gitHubUrl by remember { mutableStateOf("") }

    // Confirmation dialog state for uninstall
    var pluginToUninstall by remember { mutableStateOf<InstalledPluginState?>(null) }

    // Show confirmation dialog for uninstall
    pluginToUninstall?.let { plugin ->
        ConfirmationDialog(
            title = "Uninstall Plugin",
            message = "Are you sure you want to uninstall \"${plugin.displayName}\"? This action cannot be undone.",
            confirmText = "Uninstall",
            onConfirm = { onUninstall(plugin.pluginId) },
            onDismiss = { pluginToUninstall = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Install section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BossSecondaryButton(
                text = "From File",
                onClick = onInstallFromFile,
                enabled = !isLoading,
                icon = Icons.Default.Download,
                modifier = Modifier.weight(1f)
            )
            BossPrimaryButton(
                text = "From GitHub",
                onClick = { showGitHubDialog = true },
                enabled = !isLoading,
                modifier = Modifier.weight(1f)
            )
        }

        // GitHub URL input
        if (showGitHubDialog) {
            Spacer(Modifier.height(8.dp))
            BossCard {
                BossTextField(
                    value = gitHubUrl,
                    onValueChange = { gitHubUrl = it },
                    label = "GitHub URL",
                    placeholder = "https://github.com/owner/repo",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossSecondaryButton(
                        text = "Cancel",
                        onClick = {
                            showGitHubDialog = false
                            gitHubUrl = ""
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    BossPrimaryButton(
                        text = "Install",
                        onClick = {
                            val trimmedUrl = gitHubUrl.trim()
                            if (trimmedUrl.isNotBlank()) {
                                onInstallFromGitHub(trimmedUrl)
                                showGitHubDialog = false
                                gitHubUrl = ""
                            }
                        },
                        enabled = gitHubUrl.trim().isNotBlank()
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Installed plugins list
        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                BossEmptyState(
                    icon = Icons.Default.Extension,
                    message = "No plugins installed",
                    description = "Install from file or GitHub"
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(plugins, key = { it.pluginId }) { plugin ->
                    InstalledPluginCard(
                        plugin = plugin,
                        hasUpdate = plugin.pluginId in updateIds,
                        onToggleEnabled = { onToggleEnabled(plugin.pluginId, it) },
                        onUninstall = { pluginToUninstall = plugin },
                        onUpdate = { onUpdate(plugin.pluginId) },
                        onOpenHomepage = { plugin.url?.let { onOpenHomepage(it) } },
                        isLoading = isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: InstalledPluginState,
    hasUpdate: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onOpenHomepage: () -> Unit,
    isLoading: Boolean
) {
    val hasHomepage = !plugin.url.isNullOrBlank()

    BossCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side - clickable to open homepage
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (hasHomepage) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onOpenHomepage() }
                                .padding(end = 8.dp)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasHomepage) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open homepage",
                            modifier = Modifier.size(12.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "v${plugin.version ?: "?"}",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp
                    )
                    if (hasUpdate) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Upgrade,
                            contentDescription = "Update available",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    if (!plugin.healthy) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Plugin unhealthy",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.WarningColor
                        )
                    }
                }
                if (plugin.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = plugin.description,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasUpdate) {
                    BossPrimaryButton(
                        text = "Update",
                        onClick = onUpdate,
                        enabled = !isLoading,
                        icon = Icons.Default.Upgrade
                    )
                    Spacer(Modifier.width(8.dp))
                }
                BossToggle(
                    label = "",
                    checked = plugin.enabled,
                    onCheckedChange = onToggleEnabled,
                    enabled = !isLoading,
                    modifier = Modifier.width(60.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = !isLoading && plugin.canUnload) { onUninstall() }
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Uninstall",
                        modifier = Modifier.size(16.dp),
                        tint = if (plugin.canUnload)
                            BossThemeColors.ErrorColor
                        else
                            BossThemeColors.TextMuted.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailablePluginsTab(
    plugins: List<PluginStoreItem>,
    installedIds: Set<String>,
    updateIds: Set<String>,
    onInstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDeleteFromStore: (String) -> Unit,
    onOpenHomepage: (String) -> Unit,
    isStoreAdmin: Boolean,
    isLoading: Boolean
) {
    // Confirmation dialog state for delete from store
    var pluginToDelete by remember { mutableStateOf<PluginStoreItem?>(null) }

    // Show confirmation dialog for delete from store
    pluginToDelete?.let { plugin ->
        ConfirmationDialog(
            title = "Delete from Store",
            message = "Are you sure you want to delete \"${plugin.displayName}\" from the plugin store? This action cannot be undone.",
            confirmText = "Delete",
            onConfirm = { onDeleteFromStore(plugin.pluginId) },
            onDismiss = { pluginToDelete = null }
        )
    }

    if (plugins.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            BossEmptyState(
                icon = Icons.Default.Extension,
                message = "No plugins available",
                description = "Check back later"
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(plugins, key = { it.pluginId }) { plugin ->
                AvailablePluginCard(
                    plugin = plugin,
                    isInstalled = plugin.pluginId in installedIds,
                    hasUpdate = plugin.pluginId in updateIds,
                    onInstall = { onInstall(plugin.pluginId) },
                    onUpdate = { onUpdate(plugin.pluginId) },
                    onDeleteFromStore = { pluginToDelete = plugin },
                    onOpenHomepage = { if (plugin.url.isNotBlank()) onOpenHomepage(plugin.url) },
                    isStoreAdmin = isStoreAdmin,
                    isLoading = isLoading
                )
            }
        }
    }
}

@Composable
private fun AvailablePluginCard(
    plugin: PluginStoreItem,
    isInstalled: Boolean,
    hasUpdate: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onDeleteFromStore: () -> Unit,
    onOpenHomepage: () -> Unit,
    isStoreAdmin: Boolean,
    isLoading: Boolean
) {
    val hasHomepage = plugin.url.isNotBlank()

    BossCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side - clickable to open homepage
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (hasHomepage) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onOpenHomepage() }
                                .padding(end = 8.dp)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (hasHomepage) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open homepage",
                            modifier = Modifier.size(12.dp),
                            tint = BossThemeColors.AccentColor
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "v${plugin.version ?: "?"}",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp
                    )
                    if (plugin.verified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Verified",
                            modifier = Modifier.size(14.dp),
                            tint = BossThemeColors.SuccessColor
                        )
                    }
                }
                if (plugin.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = plugin.description,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (plugin.author.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "by ${plugin.author}",
                        color = BossThemeColors.TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    hasUpdate -> {
                        BossPrimaryButton(
                            text = "Update",
                            onClick = onUpdate,
                            enabled = !isLoading,
                            icon = Icons.Default.Upgrade
                        )
                    }
                    isInstalled -> {
                        Text(
                            text = "Installed",
                            color = BossThemeColors.SuccessColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    else -> {
                        BossPrimaryButton(
                            text = "Install",
                            onClick = onInstall,
                            enabled = !isLoading,
                            icon = Icons.Default.Download
                        )
                    }
                }

                // Delete button for store admins
                if (isStoreAdmin) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isLoading) { onDeleteFromStore() }
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete from store",
                            modifier = Modifier.size(16.dp),
                            tint = BossThemeColors.ErrorColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatesTab(
    updates: List<UpdateInfo>,
    onUpdate: (String) -> Unit,
    onUpdateAll: () -> Unit,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (updates.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                BossEmptyState(
                    icon = Icons.Default.Check,
                    message = "All plugins are up to date",
                    description = "No updates available"
                )
            }
        } else {
            // Update All section
            BossSection(
                title = "Available Updates",
                description = "${updates.size} update${if (updates.size != 1) "s" else ""} available"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    BossPrimaryButton(
                        text = "Update All (${updates.size})",
                        onClick = onUpdateAll,
                        enabled = !isLoading
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(updates, key = { it.pluginId }) { update ->
                    UpdateCard(
                        update = update,
                        onUpdate = { onUpdate(update.pluginId) },
                        isLoading = isLoading
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(
    update: UpdateInfo,
    onUpdate: () -> Unit,
    isLoading: Boolean
) {
    BossCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = update.displayName,
                        color = BossThemeColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (update.critical) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BossThemeColors.ErrorColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Critical",
                                color = BossThemeColors.ErrorColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${update.currentVersion} → ${update.newVersion}",
                    color = BossThemeColors.AccentColor,
                    fontSize = 12.sp
                )
                if (update.changelog.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = update.changelog,
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            BossPrimaryButton(
                text = "Update",
                onClick = onUpdate,
                enabled = !isLoading
            )
        }
    }
}

/**
 * Source selection for plugin JAR.
 */
private enum class JarSource {
    GITHUB,
    LOCAL_FILE
}

@Composable
private fun PublishTab(
    onFetchFromGitHub: (
        url: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (jarPath: String, manifest: ExtractedManifest) -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onBrowseJar: ((String?) -> Unit) -> Unit,
    onExtractManifest: (String, (ExtractedManifest?) -> Unit) -> Unit,
    onPublish: (
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
    ) -> Unit,
    isLoading: Boolean
) {
    var jarSource by remember { mutableStateOf(JarSource.GITHUB) }
    var gitHubUrl by remember { mutableStateOf("") }
    var fetchProgress by remember { mutableStateOf(0f) }
    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }

    var jarPath by remember { mutableStateOf("") }
    var pluginId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var homepageUrl by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var changelog by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var iconUrl by remember { mutableStateOf("") }
    var pluginType by remember { mutableStateOf(PluginType.PANEL) }
    var apiVersion by remember { mutableStateOf("1.0") }
    var minBossVersion by remember { mutableStateOf("1.0.0") }
    var publishProgress by remember { mutableStateOf(0f) }
    var publishStatus by remember { mutableStateOf<String?>(null) }
    var isPublishing by remember { mutableStateOf(false) }
    var showTypeDropdown by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        BossSection(
            title = "Publish Plugin",
            description = "Upload your plugin to the BOSS Plugin Store"
        ) {
            // Source selection tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (jarSource == JarSource.GITHUB) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                            else BossThemeColors.SurfaceColor
                        )
                        .clickable(enabled = !isLoading && !isPublishing && !isFetching) { jarSource = JarSource.GITHUB }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "From GitHub",
                        color = if (jarSource == JarSource.GITHUB) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (jarSource == JarSource.GITHUB) FontWeight.Medium else FontWeight.Normal
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (jarSource == JarSource.LOCAL_FILE) BossThemeColors.AccentColor.copy(alpha = 0.15f)
                            else BossThemeColors.SurfaceColor
                        )
                        .clickable(enabled = !isLoading && !isPublishing && !isFetching) { jarSource = JarSource.LOCAL_FILE }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "From Local File",
                        color = if (jarSource == JarSource.LOCAL_FILE) BossThemeColors.AccentColor else BossThemeColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (jarSource == JarSource.LOCAL_FILE) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // JAR source selection
            BossCard {
                when (jarSource) {
                    JarSource.GITHUB -> {
                        Column {
                            Text(
                                text = "GitHub Repository",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            BossTextField(
                                value = gitHubUrl,
                                onValueChange = { gitHubUrl = it },
                                label = "",
                                placeholder = "https://github.com/owner/repo",
                                enabled = !isLoading && !isPublishing && !isFetching,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))

                            // Fetch progress
                            if (isFetching) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(
                                        progress = fetchProgress,
                                        color = BossThemeColors.AccentColor,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    if (fetchStatus != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = fetchStatus!!,
                                            color = BossThemeColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (jarPath.isNotEmpty()) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = BossThemeColors.SuccessColor
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = jarPath.substringAfterLast("/"),
                                                color = BossThemeColors.TextPrimary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                    BossPrimaryButton(
                                        text = "Fetch",
                                        onClick = {
                                            val trimmedUrl = gitHubUrl.trim()
                                            if (trimmedUrl.isNotBlank()) {
                                                isFetching = true
                                                fetchStatus = null
                                                fetchProgress = 0f
                                                onFetchFromGitHub(
                                                    trimmedUrl,
                                                    { progress -> fetchProgress = progress },
                                                    { status -> fetchStatus = status },
                                                    { path, manifest ->
                                                        isFetching = false
                                                        jarPath = path
                                                        pluginId = manifest.pluginId
                                                        displayName = manifest.displayName
                                                        version = manifest.version
                                                        description = manifest.description
                                                        authorName = manifest.author ?: ""
                                                        homepageUrl = manifest.url ?: trimmedUrl
                                                        apiVersion = manifest.apiVersion
                                                        minBossVersion = manifest.minBossVersion.ifEmpty { "1.0.0" }
                                                        pluginType = manifest.type
                                                        fetchStatus = null
                                                    },
                                                    { error ->
                                                        isFetching = false
                                                        fetchStatus = "Error: $error"
                                                    }
                                                )
                                            }
                                        },
                                        enabled = !isLoading && !isPublishing && !isFetching && gitHubUrl.trim().isNotBlank(),
                                        icon = Icons.Default.Download
                                    )
                                }
                            }
                        }
                    }
                    JarSource.LOCAL_FILE -> {
                        Column {
                            Text(
                                text = "Plugin JAR File",
                                color = BossThemeColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (jarPath.isNotEmpty()) jarPath.substringAfterLast("/") else "No file selected",
                                    color = if (jarPath.isNotEmpty()) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                                BossSecondaryButton(
                                    text = "Browse",
                                    onClick = {
                                        onBrowseJar { selectedPath ->
                                            if (selectedPath != null) {
                                                jarPath = selectedPath
                                                // Auto-extract manifest
                                                onExtractManifest(selectedPath) { manifest ->
                                                    if (manifest != null) {
                                                        pluginId = manifest.pluginId
                                                        displayName = manifest.displayName
                                                        version = manifest.version
                                                        description = manifest.description
                                                        authorName = manifest.author ?: ""
                                                        homepageUrl = manifest.url ?: ""
                                                        apiVersion = manifest.apiVersion
                                                        minBossVersion = manifest.minBossVersion.ifEmpty { "1.0.0" }
                                                        pluginType = manifest.type
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isLoading && !isPublishing
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Plugin details form
            BossTextField(
                value = pluginId,
                onValueChange = { pluginId = it },
                label = "Plugin ID",
                placeholder = "ai.rever.boss.plugin.example",
                enabled = !isLoading && !isPublishing,
                required = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Display Name",
                placeholder = "My Awesome Plugin",
                enabled = !isLoading && !isPublishing,
                required = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BossTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = "Version",
                    placeholder = "1.0.0",
                    enabled = !isLoading && !isPublishing,
                    required = true,
                    modifier = Modifier.weight(1f)
                )
                BossTextField(
                    value = authorName,
                    onValueChange = { authorName = it },
                    label = "Author",
                    placeholder = "Your Name",
                    enabled = !isLoading && !isPublishing,
                    required = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // Plugin Type dropdown
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Plugin Type",
                    color = BossThemeColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Box {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(BossThemeColors.SurfaceColor)
                            .clickable(enabled = !isLoading && !isPublishing) { showTypeDropdown = !showTypeDropdown }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pluginType.displayText,
                                color = BossThemeColors.TextPrimary,
                                fontSize = 13.sp
                            )
                            Icon(
                                if (showTypeDropdown) Icons.Default.Close else Icons.Default.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = BossThemeColors.TextSecondary
                            )
                        }
                    }
                    if (showTypeDropdown) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(BossThemeColors.SurfaceColor)
                        ) {
                            PluginType.entries.forEach { type ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pluginType = type
                                            showTypeDropdown = false
                                        }
                                        .background(
                                            if (type == pluginType) BossThemeColors.AccentColor.copy(alpha = 0.1f)
                                            else BossThemeColors.SurfaceColor
                                        )
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = type.displayText,
                                        color = if (type == pluginType) BossThemeColors.AccentColor else BossThemeColors.TextPrimary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                BossTextField(
                    value = apiVersion,
                    onValueChange = { apiVersion = it },
                    label = "API Version",
                    placeholder = "1.0",
                    enabled = !isLoading && !isPublishing,
                    modifier = Modifier.weight(1f)
                )
                BossTextField(
                    value = minBossVersion,
                    onValueChange = { minBossVersion = it },
                    label = "Min BOSS Version",
                    placeholder = "1.0.0",
                    enabled = !isLoading && !isPublishing,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = homepageUrl,
                onValueChange = { homepageUrl = it },
                label = "Homepage URL",
                placeholder = "https://github.com/your/repo",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = iconUrl,
                onValueChange = { iconUrl = it },
                label = "Icon URL",
                placeholder = "https://example.com/icon.png",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextArea(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "Describe what your plugin does...",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextArea(
                value = changelog,
                onValueChange = { changelog = it },
                label = "Changelog",
                placeholder = "What's new in this version...",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            BossTextField(
                value = tags,
                onValueChange = { tags = it },
                label = "Tags",
                placeholder = "ui, productivity, tools (comma-separated)",
                enabled = !isLoading && !isPublishing,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // Progress indicator
            if (isPublishing) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = publishProgress,
                        color = BossThemeColors.AccentColor,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Publishing... ${(publishProgress * 100).toInt()}%",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            // Status message
            if (publishStatus != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = publishStatus!!,
                    color = if (publishStatus!!.startsWith("Success")) BossThemeColors.SuccessColor else BossThemeColors.ErrorColor,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // Publish button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                BossPrimaryButton(
                    text = "Publish",
                    onClick = {
                        if (jarPath.isNotBlank() && pluginId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank() && authorName.isNotBlank()) {
                            isPublishing = true
                            publishStatus = null
                            onPublish(
                                jarPath,
                                pluginId,
                                displayName,
                                version,
                                homepageUrl,
                                authorName,
                                description.ifBlank { null },
                                changelog.ifBlank { null },
                                tags.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                iconUrl.ifBlank { null },
                                pluginType.value,
                                apiVersion.ifBlank { "1.0" },
                                minBossVersion.ifBlank { "1.0.0" },
                                { progress -> publishProgress = progress },
                                { result ->
                                    isPublishing = false
                                    publishStatus = "Success! Plugin published with ID: $result"
                                    // Clear form
                                    jarPath = ""
                                    pluginId = ""
                                    displayName = ""
                                    version = ""
                                    homepageUrl = ""
                                    authorName = ""
                                    description = ""
                                    changelog = ""
                                    tags = ""
                                    iconUrl = ""
                                    pluginType = PluginType.PANEL
                                    apiVersion = "1.0"
                                    minBossVersion = "1.0.0"
                                },
                                { error ->
                                    isPublishing = false
                                    publishStatus = "Error: $error"
                                }
                            )
                        }
                    },
                    enabled = !isLoading && !isPublishing && jarPath.isNotBlank() && pluginId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank() && authorName.isNotBlank(),
                    icon = Icons.Default.Upload
                )
            }
        }
    }
}

private fun filterPlugins(
    plugins: List<InstalledPluginState>,
    query: String
): List<InstalledPluginState> {
    if (query.isEmpty()) return plugins
    val lowerQuery = query.lowercase()
    return plugins.filter {
        it.displayName.lowercase().contains(lowerQuery) ||
        it.pluginId.lowercase().contains(lowerQuery) ||
        it.description.lowercase().contains(lowerQuery)
    }
}

private fun filterAvailablePlugins(
    plugins: List<PluginStoreItem>,
    query: String
): List<PluginStoreItem> {
    if (query.isEmpty()) return plugins
    val lowerQuery = query.lowercase()
    return plugins.filter {
        it.displayName.lowercase().contains(lowerQuery) ||
        it.pluginId.lowercase().contains(lowerQuery) ||
        it.description.lowercase().contains(lowerQuery)
    }
}
