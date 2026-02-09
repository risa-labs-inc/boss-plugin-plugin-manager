package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Implementation of PluginManagerAPI.
 *
 * This implementation is self-contained within the plugin-manager plugin.
 * It handles:
 * - Reading/writing installed.json
 * - Fetching plugins from the store API
 * - Downloading JARs from GitHub releases
 * - Delegating load/unload to PluginLoaderDelegate (provided by BossConsole)
 */
class PluginManagerAPIImpl(
    private val scope: CoroutineScope,
    private val loaderDelegate: PluginLoaderDelegate?
) : PluginManagerAPI {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _installedPlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    private val _events = MutableSharedFlow<PluginEvent>()

    private val pluginsDir: File
        get() = File(loaderDelegate?.getPluginsDirectory() ?: "${System.getProperty("user.home")}/.boss/plugins")

    private val installedJsonFile: File
        get() = File(pluginsDir, "installed.json")

    companion object {
        private const val STORE_API_URL = "https://api.risaboss.com/functions/v1/plugin-store"
        private const val GITHUB_API_URL = "https://api.github.com"
    }

    init {
        // Load installed plugins on initialization
        scope.launch {
            refreshInstalledPlugins()
        }
    }

    // ========================================
    // INSTALLED PLUGINS
    // ========================================

    override fun getInstalledPlugins(): List<PluginInfo> = _installedPlugins.value

    override fun observeInstalledPlugins(): StateFlow<List<PluginInfo>> = _installedPlugins.asStateFlow()

    override fun isPluginInstalled(pluginId: String): Boolean =
        _installedPlugins.value.any { it.pluginId == pluginId }

    override fun getInstalledPlugin(pluginId: String): PluginInfo? =
        _installedPlugins.value.find { it.pluginId == pluginId }

    private suspend fun refreshInstalledPlugins() {
        val plugins = readInstalledPlugins()
        _installedPlugins.value = plugins
    }

    private fun readInstalledPlugins(): List<PluginInfo> {
        if (!installedJsonFile.exists()) {
            return emptyList()
        }

        return try {
            val content = installedJsonFile.readText()
            val installed = json.decodeFromString<InstalledPluginsFile>(content)
            installed.plugins.map { entry ->
                PluginInfo(
                    pluginId = entry.pluginId,
                    displayName = entry.displayName,
                    version = entry.version,
                    jarPath = entry.jarPath,
                    installedAt = entry.installedAt,
                    isEnabled = entry.enabled,
                    url = entry.githubUrl
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeInstalledPlugins(plugins: List<PluginInfo>) {
        pluginsDir.mkdirs()

        val entries = plugins.map { plugin ->
            InstalledPluginEntry(
                pluginId = plugin.pluginId,
                displayName = plugin.displayName,
                version = plugin.version,
                jarPath = plugin.jarPath,
                installedAt = plugin.installedAt,
                githubUrl = plugin.url,
                enabled = plugin.isEnabled
            )
        }

        val file = InstalledPluginsFile(plugins = entries)
        installedJsonFile.writeText(json.encodeToString(InstalledPluginsFile.serializer(), file))
    }

    // ========================================
    // PLUGIN STORE
    // ========================================

    override suspend fun fetchStorePlugins(
        query: String?,
        category: String?
    ): Result<List<PluginStoreItem>> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = StringBuilder("$STORE_API_URL/list")
            val params = mutableListOf<String>()
            if (!query.isNullOrBlank()) params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            if (!category.isNullOrBlank()) params.add("category=${java.net.URLEncoder.encode(category, "UTF-8")}")
            if (params.isNotEmpty()) {
                urlBuilder.append("?").append(params.joinToString("&"))
            }

            val connection = URL(urlBuilder.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val items = json.decodeFromString<List<PluginStoreItem>>(response)
                _events.emit(PluginEvent.StoreRefreshed(items.size))
                Result.success(items)
            } else {
                Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$STORE_API_URL/details?plugin_id=${java.net.URLEncoder.encode(pluginId, "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val item = json.decodeFromString<PluginStoreItem>(response)
                Result.success(item)
            } else {
                Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkForUpdates(): Map<String, String> = withContext(Dispatchers.IO) {
        val updates = mutableMapOf<String, String>()
        val installed = getInstalledPlugins()

        for (plugin in installed) {
            if (plugin.isSystemPlugin) continue // Skip system plugins

            try {
                val details = fetchPluginDetails(plugin.pluginId).getOrNull()
                if (details != null && isNewerVersion(details.version, plugin.version)) {
                    updates[plugin.pluginId] = details.version
                }
            } catch (e: Exception) {
                // Skip plugins we can't check
            }
        }

        updates
    }

    // ========================================
    // INSTALL / UNINSTALL
    // ========================================

    override suspend fun installPlugin(pluginId: String): InstallResult = withContext(Dispatchers.IO) {
        // Check if already installed
        val existing = getInstalledPlugin(pluginId)
        if (existing != null) {
            return@withContext InstallResult.AlreadyInstalled(existing.version)
        }

        // Fetch plugin details from store
        val detailsResult = fetchPluginDetails(pluginId)
        if (detailsResult.isFailure) {
            return@withContext InstallResult.DownloadFailed("Plugin not found in store: ${detailsResult.exceptionOrNull()?.message}")
        }

        val storeItem = detailsResult.getOrThrow()

        // Download from GitHub
        installFromGitHub(storeItem.githubUrl)
    }

    override suspend fun installFromGitHub(githubUrl: String): InstallResult = withContext(Dispatchers.IO) {
        try {
            // Parse GitHub URL to get owner/repo
            val regex = Regex("""github\.com/([^/]+)/([^/]+)""")
            val match = regex.find(githubUrl)
                ?: return@withContext InstallResult.DownloadFailed("Invalid GitHub URL: $githubUrl")

            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")

            // Get latest release
            val releaseUrl = "$GITHUB_API_URL/repos/$owner/$repo/releases/latest"
            val releaseConnection = URL(releaseUrl).openConnection() as HttpURLConnection
            releaseConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            releaseConnection.connectTimeout = 10000
            releaseConnection.readTimeout = 10000

            if (releaseConnection.responseCode != 200) {
                return@withContext InstallResult.DownloadFailed("Could not fetch release: HTTP ${releaseConnection.responseCode}")
            }

            val releaseJson = releaseConnection.inputStream.bufferedReader().readText()

            // Find JAR asset URL
            val jarUrlMatch = Regex(""""browser_download_url"\s*:\s*"([^"]+\.jar)"""").find(releaseJson)
                ?: return@withContext InstallResult.DownloadFailed("No JAR asset found in release")

            val jarUrl = jarUrlMatch.groupValues[1]
            val jarFileName = jarUrl.substringAfterLast("/")

            // Download JAR
            pluginsDir.mkdirs()
            val destFile = File(pluginsDir, jarFileName)

            val downloadConnection = URL(jarUrl).openConnection() as HttpURLConnection
            downloadConnection.instanceFollowRedirects = true
            downloadConnection.connectTimeout = 30000
            downloadConnection.readTimeout = 60000

            if (downloadConnection.responseCode != 200) {
                return@withContext InstallResult.DownloadFailed("Download failed: HTTP ${downloadConnection.responseCode}")
            }

            destFile.outputStream().use { output ->
                downloadConnection.inputStream.copyTo(output)
            }

            // Load the plugin via delegate
            val pluginInfo = loaderDelegate?.loadPlugin(destFile.absolutePath)
                ?: return@withContext InstallResult.LoadFailed("No plugin loader available")

            // Update installed.json
            val currentPlugins = _installedPlugins.value.toMutableList()
            currentPlugins.add(pluginInfo.copy(
                jarPath = destFile.absolutePath,
                installedAt = System.currentTimeMillis(),
                url = githubUrl
            ))
            writeInstalledPlugins(currentPlugins)
            _installedPlugins.value = currentPlugins

            _events.emit(PluginEvent.PluginInstalled(pluginInfo))
            InstallResult.Success(pluginInfo)

        } catch (e: Exception) {
            InstallResult.DownloadFailed(e.message ?: "Unknown error")
        }
    }

    override suspend fun installFromFile(jarPath: String): InstallResult = withContext(Dispatchers.IO) {
        try {
            val jarFile = File(jarPath)
            if (!jarFile.exists()) {
                return@withContext InstallResult.DownloadFailed("File not found: $jarPath")
            }

            // Copy to plugins directory if not already there
            val destFile = if (jarFile.parentFile.absolutePath == pluginsDir.absolutePath) {
                jarFile
            } else {
                val dest = File(pluginsDir, jarFile.name)
                jarFile.copyTo(dest, overwrite = true)
                dest
            }

            // Load the plugin
            val pluginInfo = loaderDelegate?.loadPlugin(destFile.absolutePath)
                ?: return@withContext InstallResult.LoadFailed("No plugin loader available")

            // Update installed.json
            val currentPlugins = _installedPlugins.value.toMutableList()
            currentPlugins.removeAll { it.pluginId == pluginInfo.pluginId }
            currentPlugins.add(pluginInfo.copy(
                jarPath = destFile.absolutePath,
                installedAt = System.currentTimeMillis()
            ))
            writeInstalledPlugins(currentPlugins)
            _installedPlugins.value = currentPlugins

            _events.emit(PluginEvent.PluginInstalled(pluginInfo))
            InstallResult.Success(pluginInfo)

        } catch (e: Exception) {
            InstallResult.DownloadFailed(e.message ?: "Unknown error")
        }
    }

    override suspend fun uninstallPlugin(pluginId: String): UninstallResult = withContext(Dispatchers.IO) {
        val plugin = getInstalledPlugin(pluginId)
            ?: return@withContext UninstallResult.NotFound(pluginId)

        if (plugin.isSystemPlugin || !plugin.canUnload) {
            return@withContext UninstallResult.CannotUnload("System plugins cannot be uninstalled")
        }

        try {
            // Unload from runtime
            val unloaded = loaderDelegate?.unloadPlugin(pluginId) ?: false
            if (!unloaded && loaderDelegate != null) {
                return@withContext UninstallResult.Failed("Failed to unload plugin from runtime")
            }

            // Delete JAR file
            if (plugin.jarPath.isNotBlank()) {
                val jarFile = File(plugin.jarPath)
                if (jarFile.exists()) {
                    jarFile.delete()
                }
            }

            // Update installed.json
            val currentPlugins = _installedPlugins.value.toMutableList()
            currentPlugins.removeAll { it.pluginId == pluginId }
            writeInstalledPlugins(currentPlugins)
            _installedPlugins.value = currentPlugins

            _events.emit(PluginEvent.PluginUninstalled(pluginId))
            UninstallResult.Success

        } catch (e: Exception) {
            UninstallResult.Failed(e.message ?: "Unknown error")
        }
    }

    override suspend fun updatePlugin(pluginId: String): InstallResult = withContext(Dispatchers.IO) {
        val existing = getInstalledPlugin(pluginId)
            ?: return@withContext InstallResult.DownloadFailed("Plugin not installed: $pluginId")

        val previousVersion = existing.version

        // Uninstall current version
        uninstallPlugin(pluginId)

        // Install latest version
        val result = if (existing.url.isNotBlank()) {
            installFromGitHub(existing.url)
        } else {
            installPlugin(pluginId)
        }

        // Emit update event if successful
        if (result is InstallResult.Success) {
            _events.emit(PluginEvent.PluginUpdated(result.plugin, previousVersion))
        }

        result
    }

    // ========================================
    // ENABLE / DISABLE
    // ========================================

    override suspend fun enablePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val plugin = getInstalledPlugin(pluginId) ?: return@withContext false

        if (plugin.isEnabled) return@withContext true

        // Load the plugin
        val loaded = loaderDelegate?.loadPlugin(plugin.jarPath) != null

        if (loaded) {
            // Update enabled state
            val currentPlugins = _installedPlugins.value.map {
                if (it.pluginId == pluginId) it.copy(isEnabled = true) else it
            }
            writeInstalledPlugins(currentPlugins)
            _installedPlugins.value = currentPlugins
            _events.emit(PluginEvent.PluginEnabled(pluginId))
        }

        loaded
    }

    override suspend fun disablePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val plugin = getInstalledPlugin(pluginId) ?: return@withContext false

        if (!plugin.isEnabled) return@withContext true

        if (plugin.isSystemPlugin || !plugin.canUnload) {
            return@withContext false
        }

        // Unload the plugin
        val unloaded = loaderDelegate?.unloadPlugin(pluginId) ?: true

        if (unloaded) {
            // Update enabled state
            val currentPlugins = _installedPlugins.value.map {
                if (it.pluginId == pluginId) it.copy(isEnabled = false) else it
            }
            writeInstalledPlugins(currentPlugins)
            _installedPlugins.value = currentPlugins
            _events.emit(PluginEvent.PluginDisabled(pluginId))
        }

        unloaded
    }

    // ========================================
    // EVENTS
    // ========================================

    override fun observeEvents(): Flow<PluginEvent> = _events.asSharedFlow()

    // ========================================
    // HELPERS
    // ========================================

    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        val newParts = newVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(newParts.size, currentParts.size)) {
            val newPart = newParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (newPart > currentPart) return true
            if (newPart < currentPart) return false
        }
        return false
    }
}
