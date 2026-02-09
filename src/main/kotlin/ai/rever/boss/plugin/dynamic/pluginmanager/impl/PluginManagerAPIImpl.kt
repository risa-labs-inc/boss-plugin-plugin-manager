package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Implementation of PluginManagerAPI.
 *
 * This implementation uses the PluginLoaderDelegate from plugin-api to interact
 * with BossConsole's DynamicPluginManager. It handles:
 * - Getting loaded plugins from BossConsole
 * - Fetching plugins from the store API
 * - Downloading JARs from GitHub releases
 * - Delegating load/unload to PluginLoaderDelegate
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
        // Public Supabase anon key for API access (safe to include, only allows public operations)
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBjbndxYW1xZG5zYWRyYW51Zmp2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzExNzQ0MjgsImV4cCI6MjA0Njc1MDQyOH0.VEMx2CWpLk2OzGXZY5FRN3dyHlHWZnH5EKs5SMx_Q6Y"
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

    /**
     * Refresh installed plugins from the delegate.
     * This gets the actual loaded plugins from BossConsole.
     */
    suspend fun refreshInstalledPlugins() {
        val plugins = if (loaderDelegate != null) {
            // Get loaded plugins from BossConsole via delegate
            loaderDelegate.getLoadedPlugins().map { it.toPluginInfo() }
        } else {
            // Fallback to reading installed.json
            readInstalledPluginsFromFile()
        }
        _installedPlugins.value = plugins
    }

    /**
     * Convert LoadedPluginInfo from plugin-api to our local PluginInfo.
     */
    private fun LoadedPluginInfo.toPluginInfo(): PluginInfo {
        return PluginInfo(
            pluginId = pluginId,
            displayName = displayName,
            version = version,
            description = description,
            author = author,
            url = url,
            type = type,
            apiVersion = apiVersion,
            minBossVersion = minBossVersion,
            isSystemPlugin = isSystemPlugin,
            canUnload = canUnload,
            loadPriority = loadPriority,
            isEnabled = isEnabled,
            jarPath = jarPath,
            installedAt = installedAt
        )
    }

    private fun readInstalledPluginsFromFile(): List<PluginInfo> {
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
            params.add("page=1")
            params.add("pageSize=50")
            params.add("sortBy=downloads")
            if (!query.isNullOrBlank()) params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            if (!category.isNullOrBlank()) params.add("category=${java.net.URLEncoder.encode(category, "UTF-8")}")
            urlBuilder.append("?").append(params.joinToString("&"))

            val connection = URL(urlBuilder.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            // Use the public anon key for API access
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val listResponse = json.decodeFromString<PluginListResponse>(response)
                _events.emit(PluginEvent.StoreRefreshed(listResponse.plugins.size))
                Result.success(listResponse.plugins)
            } else {
                val errorBody = try { connection.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                Result.failure(Exception("HTTP ${connection.responseCode}: ${connection.responseMessage} - $errorBody"))
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
                val detailsVersion = details?.version
                if (details != null && detailsVersion != null && isNewerVersion(detailsVersion, plugin.version)) {
                    updates[plugin.pluginId] = detailsVersion
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
            releaseConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Manager")
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
            downloadConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Manager")
            downloadConnection.connectTimeout = 30000
            downloadConnection.readTimeout = 60000

            if (downloadConnection.responseCode != 200) {
                return@withContext InstallResult.DownloadFailed("Download failed: HTTP ${downloadConnection.responseCode}")
            }

            destFile.outputStream().use { output ->
                downloadConnection.inputStream.copyTo(output)
            }

            // Load the plugin via delegate
            val loadedInfo = loaderDelegate?.loadPlugin(destFile.absolutePath)
                ?: return@withContext InstallResult.LoadFailed("No plugin loader available")

            val pluginInfo = loadedInfo.toPluginInfo().copy(
                jarPath = destFile.absolutePath,
                installedAt = System.currentTimeMillis(),
                url = githubUrl
            )

            // Refresh installed plugins
            refreshInstalledPlugins()

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

            // Load the plugin via delegate
            val loadedInfo = loaderDelegate?.loadPlugin(destFile.absolutePath)
                ?: return@withContext InstallResult.LoadFailed("No plugin loader available")

            val pluginInfo = loadedInfo.toPluginInfo().copy(
                jarPath = destFile.absolutePath,
                installedAt = System.currentTimeMillis()
            )

            // Refresh installed plugins
            refreshInstalledPlugins()

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
            // Unload from runtime via delegate
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

            // Refresh installed plugins
            refreshInstalledPlugins()

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
        val enabled = loaderDelegate?.enablePlugin(pluginId) ?: false
        if (enabled) {
            refreshInstalledPlugins()
            _events.emit(PluginEvent.PluginEnabled(pluginId))
        }
        enabled
    }

    override suspend fun disablePlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        val disabled = loaderDelegate?.disablePlugin(pluginId) ?: false
        if (disabled) {
            refreshInstalledPlugins()
            _events.emit(PluginEvent.PluginDisabled(pluginId))
        }
        disabled
    }

    // ========================================
    // ADMIN
    // ========================================

    /**
     * Check if current user is a store admin.
     */
    fun isCurrentUserAdmin(): Boolean = loaderDelegate?.isCurrentUserAdmin() ?: false

    // ========================================
    // EVENTS
    // ========================================

    override fun observeEvents(): Flow<PluginEvent> = _events.asSharedFlow()

    // ========================================
    // STORE ADMIN (PUBLISH)
    // ========================================

    override suspend fun deleteFromStore(pluginId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "$STORE_API_URL/delete"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val body = """{"plugin_id": "$pluginId"}"""
            connection.outputStream.bufferedWriter().use { it.write(body) }

            if (connection.responseCode == 200) {
                Result.success(Unit)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Result.failure(Exception("HTTP ${connection.responseCode}: ${errorBody ?: connection.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchFromGitHubForPublish(
        url: String,
        onProgress: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onSuccess: (jarPath: String, manifest: ExtractedManifest) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onStatus("Parsing GitHub URL...")
            onProgress(0.1f)

            // Parse GitHub URL
            val regex = Regex("""github\.com/([^/]+)/([^/]+)""")
            val match = regex.find(url)
            if (match == null) {
                onError("Invalid GitHub URL")
                return@withContext
            }

            val owner = match.groupValues[1]
            val repo = match.groupValues[2].removeSuffix(".git")

            onStatus("Fetching release info...")
            onProgress(0.2f)

            // Get latest release
            val releaseUrl = "$GITHUB_API_URL/repos/$owner/$repo/releases/latest"
            val releaseConnection = URL(releaseUrl).openConnection() as HttpURLConnection
            releaseConnection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            releaseConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Manager")
            releaseConnection.connectTimeout = 10000
            releaseConnection.readTimeout = 10000

            if (releaseConnection.responseCode != 200) {
                onError("Could not fetch release: HTTP ${releaseConnection.responseCode}")
                return@withContext
            }

            val releaseJson = releaseConnection.inputStream.bufferedReader().readText()
            onProgress(0.3f)

            // Find JAR asset URL
            val jarUrlMatch = Regex(""""browser_download_url"\s*:\s*"([^"]+\.jar)"""").find(releaseJson)
            if (jarUrlMatch == null) {
                onError("No JAR asset found in release")
                return@withContext
            }

            val jarUrl = jarUrlMatch.groupValues[1]
            val jarFileName = jarUrl.substringAfterLast("/")

            onStatus("Downloading $jarFileName...")
            onProgress(0.4f)

            // Download JAR to temp directory
            val tempDir = File(System.getProperty("java.io.tmpdir"), "boss-publish")
            tempDir.mkdirs()
            val destFile = File(tempDir, jarFileName)

            val downloadConnection = URL(jarUrl).openConnection() as HttpURLConnection
            downloadConnection.instanceFollowRedirects = true
            downloadConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Manager")
            downloadConnection.connectTimeout = 30000
            downloadConnection.readTimeout = 60000

            if (downloadConnection.responseCode != 200) {
                onError("Download failed: HTTP ${downloadConnection.responseCode}")
                return@withContext
            }

            val contentLength = downloadConnection.contentLength
            var downloaded = 0L
            destFile.outputStream().use { output ->
                downloadConnection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(8192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (contentLength > 0) {
                            val progress = 0.4f + (downloaded.toFloat() / contentLength) * 0.4f
                            onProgress(progress)
                        }
                    }
                }
            }

            onStatus("Extracting manifest...")
            onProgress(0.9f)

            // Extract manifest from JAR
            val manifest = extractManifestFromJar(destFile.absolutePath)
            if (manifest == null) {
                onError("Could not read plugin manifest from JAR")
                return@withContext
            }

            onProgress(1.0f)
            onSuccess(destFile.absolutePath, manifest)

        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override suspend fun browseForPluginJar(onResult: (String?) -> Unit) = withContext(Dispatchers.Main) {
        try {
            val fileDialog = java.awt.FileDialog(null as java.awt.Frame?, "Select Plugin JAR", java.awt.FileDialog.LOAD)
            fileDialog.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".jar") }
            fileDialog.isVisible = true

            val selectedFile = fileDialog.file
            val directory = fileDialog.directory

            if (selectedFile != null && directory != null) {
                onResult(File(directory, selectedFile).absolutePath)
            } else {
                onResult(null)
            }
        } catch (e: Exception) {
            onResult(null)
        }
    }

    override suspend fun extractManifest(jarPath: String, onResult: (ExtractedManifest?) -> Unit) = withContext(Dispatchers.IO) {
        val manifest = extractManifestFromJar(jarPath)
        withContext(Dispatchers.Main) {
            onResult(manifest)
        }
    }

    private fun extractManifestFromJar(jarPath: String): ExtractedManifest? {
        return try {
            val jarFile = java.util.jar.JarFile(jarPath)
            val manifestEntry = jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
            if (manifestEntry != null) {
                val manifestJson = jarFile.getInputStream(manifestEntry).bufferedReader().readText()
                jarFile.close()

                // Parse the manifest JSON
                val manifestData = json.decodeFromString<PluginManifestData>(manifestJson)
                ExtractedManifest(
                    pluginId = manifestData.pluginId,
                    displayName = manifestData.displayName,
                    version = manifestData.version,
                    description = manifestData.description ?: "",
                    author = manifestData.author,
                    url = manifestData.url,
                    apiVersion = manifestData.apiVersion ?: "1.0",
                    minBossVersion = manifestData.minBossVersion ?: "",
                    type = PluginType.fromString(manifestData.type ?: "panel")
                )
            } else {
                jarFile.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun publishPlugin(
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
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)

            val jarFile = File(jarPath)
            if (!jarFile.exists()) {
                onError("JAR file not found: $jarPath")
                return@withContext
            }

            onProgress(0.2f)

            // Read JAR file as base64
            val jarBytes = jarFile.readBytes()
            val jarBase64 = java.util.Base64.getEncoder().encodeToString(jarBytes)

            onProgress(0.4f)

            // Build the publish request
            val requestBody = buildString {
                append("{")
                append("\"plugin_id\": \"$pluginId\",")
                append("\"display_name\": \"${displayName.replace("\"", "\\\"")}\",")
                append("\"version\": \"$version\",")
                append("\"type\": \"$pluginType\",")
                append("\"api_version\": \"$apiVersion\",")
                append("\"min_boss_version\": \"$minBossVersion\",")
                append("\"author\": \"${authorName.replace("\"", "\\\"")}\",")
                append("\"url\": \"${homepageUrl.replace("\"", "\\\"")}\",")
                if (!description.isNullOrBlank()) {
                    append("\"description\": \"${description.replace("\"", "\\\"").replace("\n", "\\n")}\",")
                }
                if (!changelog.isNullOrBlank()) {
                    append("\"changelog\": \"${changelog.replace("\"", "\\\"").replace("\n", "\\n")}\",")
                }
                if (tags.isNotEmpty()) {
                    append("\"tags\": [${tags.joinToString(",") { "\"$it\"" }}],")
                }
                if (!iconUrl.isNullOrBlank()) {
                    append("\"icon_url\": \"${iconUrl.replace("\"", "\\\"")}\",")
                }
                append("\"jar_base64\": \"$jarBase64\"")
                append("}")
            }

            onProgress(0.6f)

            // Send publish request
            val url = "$STORE_API_URL/publish"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 60000
            connection.readTimeout = 120000

            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            onProgress(0.8f)

            if (connection.responseCode == 200 || connection.responseCode == 201) {
                val response = connection.inputStream.bufferedReader().readText()
                onProgress(1.0f)
                onSuccess(pluginId)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                onError("Publish failed: HTTP ${connection.responseCode} - ${errorBody ?: connection.responseMessage}")
            }

        } catch (e: Exception) {
            onError(e.message ?: "Unknown error during publish")
        }
    }

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
