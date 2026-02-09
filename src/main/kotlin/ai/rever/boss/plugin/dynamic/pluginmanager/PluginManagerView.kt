package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plugin Manager view displaying installed plugins and store.
 */
@Composable
fun PluginManagerView(viewModel: PluginManagerViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val installedPlugins by viewModel.filteredInstalledPlugins.collectAsState()
    val storePlugins by viewModel.filteredStorePlugins.collectAsState()
    val availableUpdates by viewModel.availableUpdates.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        PluginManagerHeader(
            selectedTab = selectedTab,
            onTabSelected = viewModel::selectTab,
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::setSearchQuery
        )

        // Error banner
        error?.let { errorMessage ->
            ErrorBanner(
                message = errorMessage,
                onDismiss = viewModel::clearError
            )
        }

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                PluginManagerTab.INSTALLED -> {
                    InstalledPluginsList(
                        plugins = installedPlugins,
                        availableUpdates = availableUpdates,
                        onUninstall = viewModel::uninstallPlugin,
                        onUpdate = viewModel::updatePlugin,
                        onEnable = viewModel::enablePlugin,
                        onDisable = viewModel::disablePlugin,
                        onCheckUpdates = viewModel::checkForUpdates
                    )
                }
                PluginManagerTab.STORE -> {
                    StorePluginsList(
                        plugins = storePlugins,
                        installedPluginIds = installedPlugins.map { it.pluginId }.toSet(),
                        onInstall = viewModel::installPlugin,
                        onRefresh = viewModel::refreshStore
                    )
                }
            }

            // Loading overlay
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginManagerHeader(
    selectedTab: PluginManagerTab,
    onTabSelected: (PluginManagerTab) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        // Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Plugin Manager",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tabs
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            TabButton(
                text = "Installed",
                isSelected = selectedTab == PluginManagerTab.INSTALLED,
                onClick = { onTabSelected(PluginManagerTab.INSTALLED) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TabButton(
                text = "Store",
                isSelected = selectedTab == PluginManagerTab.STORE,
                onClick = { onTabSelected(PluginManagerTab.STORE) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search plugins...", fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colors.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colors.surface.copy(alpha = 0.3f)
    }

    val textColor = if (isSelected) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = textColor
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
            .background(Color.Red.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color.Red
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = Color.Red,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(14.dp),
                tint = Color.Red
            )
        }
    }
}

@Composable
private fun InstalledPluginsList(
    plugins: List<PluginInfo>,
    availableUpdates: Map<String, String>,
    onUninstall: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onCheckUpdates: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Actions bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${plugins.size} plugins",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
            TextButton(
                onClick = onCheckUpdates,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Check Updates", fontSize = 11.sp)
            }
        }

        if (plugins.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Extension,
                message = "No plugins installed",
                subMessage = "Visit the Store to find plugins"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plugins, key = { it.pluginId }) { plugin ->
                    InstalledPluginCard(
                        plugin = plugin,
                        hasUpdate = availableUpdates.containsKey(plugin.pluginId),
                        newVersion = availableUpdates[plugin.pluginId],
                        onUninstall = { onUninstall(plugin.pluginId) },
                        onUpdate = { onUpdate(plugin.pluginId) },
                        onEnable = { onEnable(plugin.pluginId) },
                        onDisable = { onDisable(plugin.pluginId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: PluginInfo,
    hasUpdate: Boolean,
    newVersion: String?,
    onUninstall: () -> Unit,
    onUpdate: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Plugin icon/type indicator
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colors.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (plugin.type) {
                            "tab" -> Icons.Outlined.Tab
                            "service" -> Icons.Outlined.Settings
                            else -> Icons.Outlined.Extension
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colors.primary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = plugin.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (plugin.isSystemPlugin) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(text = "System", color = MaterialTheme.colors.primary)
                        }
                        if (hasUpdate) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(text = "Update", color = Color(0xFF4CAF50))
                        }
                    }
                    Text(
                        text = "v${plugin.version}" + if (hasUpdate && newVersion != null) " → v$newVersion" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = plugin.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Actions
            if (!plugin.isSystemPlugin) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (hasUpdate) {
                        SmallButton(
                            text = "Update",
                            onClick = onUpdate,
                            backgroundColor = Color(0xFF4CAF50).copy(alpha = 0.1f),
                            textColor = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    if (plugin.canUnload) {
                        if (plugin.isEnabled) {
                            SmallButton(
                                text = "Disable",
                                onClick = onDisable,
                                backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                                textColor = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        } else {
                            SmallButton(
                                text = "Enable",
                                onClick = onEnable,
                                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
                                textColor = MaterialTheme.colors.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    SmallButton(
                        text = "Uninstall",
                        onClick = onUninstall,
                        backgroundColor = Color.Red.copy(alpha = 0.1f),
                        textColor = Color.Red
                    )
                }
            }
        }
    }
}

@Composable
private fun StorePluginsList(
    plugins: List<PluginStoreItem>,
    installedPluginIds: Set<String>,
    onInstall: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Actions bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${plugins.size} available",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
            TextButton(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Refresh", fontSize = 11.sp)
            }
        }

        if (plugins.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Store,
                message = "No plugins in store",
                subMessage = "Pull to refresh or check your connection"
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plugins, key = { it.pluginId }) { plugin ->
                    StorePluginCard(
                        plugin = plugin,
                        isInstalled = plugin.pluginId in installedPluginIds,
                        onInstall = { onInstall(plugin.pluginId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorePluginCard(
    plugin: PluginStoreItem,
    isInstalled: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        backgroundColor = MaterialTheme.colors.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Plugin icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colors.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (plugin.type) {
                            "tab" -> Icons.Outlined.Tab
                            "service" -> Icons.Outlined.Settings
                            else -> Icons.Outlined.Extension
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colors.primary
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${plugin.version}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                        if (plugin.author.isNotBlank()) {
                            Text(
                                text = " • ${plugin.author}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Install button
                if (isInstalled) {
                    Badge(text = "Installed", color = Color(0xFF4CAF50))
                } else {
                    SmallButton(
                        text = "Install",
                        onClick = onInstall,
                        backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.1f),
                        textColor = MaterialTheme.colors.primary
                    )
                }
            }

            if (plugin.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = plugin.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Stats
            if (plugin.downloadCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "${plugin.downloadCount}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun SmallButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    subMessage: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colors.onBackground.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
            )
            Text(
                text = subMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
            )
        }
    }
}
