package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import ai.rever.boss.plugin.dynamic.pluginmanager.realtime.PluginStoreRealtimeClient
import ai.rever.boss.plugin.dynamic.pluginmanager.realtime.StoreChangeEvent
import ai.rever.boss.plugin.dynamic.pluginmanager.realtime.withHostClassLoader
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
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
 * - Fetching plugins from the store via Supabase Postgrest
 * - Live updates via Supabase Realtime
 * - Downloading JARs from GitHub releases
 * - Delegating load/unload to PluginLoaderDelegate
 *
 * Uses [withHostClassLoader] to swap the thread's context classloader to the
 * host's classloader when creating SupabaseClients, so Ktor's ServiceLoader
 * can discover the CIO engine from the host's META-INF/services.
 *
 * Edge Functions are kept only for operations needing server-side logic:
 * download (signed URLs), publish, and admin delete.
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
        private const val SUPABASE_URL = "https://api.risaboss.com"
        private const val STORE_API_URL = "https://api.risaboss.com/functions/v1/plugin-store"
        private const val GITHUB_API_URL = "https://api.github.com"
        // Public Supabase anon key for API access (safe to include, only allows public operations)
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBjbndxYW1xZG5zYWRyYW51Zmp2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzExNzQ0MjgsImV4cCI6MjA0Njc1MDQyOH0.VEMx2CWpLk2OzGXZY5FRN3dyHlHWZnH5EKs5SMx_Q6Y"
    }

    // Supabase Postgrest client for database reads (created with host classloader)
    private val supabaseClient: SupabaseClient = withHostClassLoader {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }

    // Realtime client for live updates
    val realtimeClient = PluginStoreRealtimeClient(SUPABASE_URL, SUPABASE_ANON_KEY)
    val storeChanges: SharedFlow<StoreChangeEvent> = realtimeClient.storeChanges

    fun connectRealtime() = realtimeClient.connect()
    fun disconnectRealtime() = realtimeClient.disconnect()

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
            val rows = supabaseClient.from("plugins")
                .select(Columns.ALL) {
                    filter {
                        eq("published", true)
                    }
                    if (!query.isNullOrBlank()) {
                        filter {
                            ilike("display_name", "%$query%")
                        }
                    }
                    range(0, 49)
                }
                .decodeList<PluginRow>()

            val storeItems = rows.map { row ->
                val latestVersion = try {
                    supabaseClient.from("plugin_versions")
                        .select(Columns.ALL) {
                            filter {
                                eq("plugin_id", row.id)
                            }
                            range(0, 0)
                        }
                        .decodeList<PluginVersionRow>()
                        .firstOrNull()
                } catch (_: Exception) {
                    null
                }

                PluginStoreItem(
                    id = row.id,
                    pluginId = row.pluginId,
                    displayName = row.displayName,
                    version = latestVersion?.version,
                    latestVersion = latestVersion?.version,
                    description = row.description ?: "",
                    author = row.authorName ?: "",
                    url = row.homepageUrl ?: "",
                    githubUrl = row.homepageUrl ?: "",
                    homepageUrl = row.homepageUrl ?: "",
                    type = row.type ?: "panel",
                    apiVersion = row.apiVersion ?: "",
                    minBossVersion = latestVersion?.minBossVersion ?: "",
                    verified = row.verified,
                    iconUrl = row.iconUrl ?: "",
                    createdAt = row.createdAt ?: "",
                    updatedAt = row.updatedAt ?: ""
                )
            }

            _events.emit(PluginEvent.StoreRefreshed(storeItems.size))
            Result.success(storeItems)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem> = withContext(Dispatchers.IO) {
        try {
            val row = supabaseClient.from("plugins")
                .select(Columns.ALL) {
                    filter {
                        eq("plugin_id", pluginId)
                    }
                }
                .decodeList<PluginRow>()
                .firstOrNull()
                ?: return@withContext Result.failure(Exception("Plugin not found: $pluginId"))

            val versions = try {
                supabaseClient.from("plugin_versions")
                    .select(Columns.ALL) {
                        filter {
                            eq("plugin_id", row.id)
                        }
                    }
                    .decodeList<PluginVersionRow>()
            } catch (_: Exception) {
                emptyList()
            }

            val latestVersion = versions.firstOrNull()

            val item = PluginStoreItem(
                id = row.id,
                pluginId = row.pluginId,
                displayName = row.displayName,
                version = latestVersion?.version,
                latestVersion = latestVersion?.version,
                description = row.description ?: "",
                author = row.authorName ?: "",
                url = row.homepageUrl ?: "",
                githubUrl = row.homepageUrl ?: "",
                homepageUrl = row.homepageUrl ?: "",
                type = row.type ?: "panel",
                apiVersion = row.apiVersion ?: "",
                minBossVersion = latestVersion?.minBossVersion ?: "",
                verified = row.verified,
                iconUrl = row.iconUrl ?: "",
                createdAt = row.createdAt ?: "",
                updatedAt = row.updatedAt ?: ""
            )

            Result.success(item)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkForUpdates(): Map<String, String> = withContext(Dispatchers.IO) {
        val updates = mutableMapOf<String, String>()
        val installed = getInstalledPlugins()

        for (plugin in installed) {
            try {
                val details = fetchPluginDetails(plugin.pluginId).getOrNull()
                val detailsVersion = details?.latestVersion ?: details?.version
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

        // Try to download directly from plugin store first
        val downloadResult = downloadFromStore(pluginId)
        if (downloadResult is InstallResult.Success) {
            return@withContext downloadResult
        }

        // Fallback: fetch plugin details and try GitHub
        val detailsResult = fetchPluginDetails(pluginId)
        if (detailsResult.isFailure) {
            // Return the store download error if we also can't get details
            if (downloadResult is InstallResult.DownloadFailed) {
                return@withContext downloadResult
            }
            return@withContext InstallResult.DownloadFailed("Plugin not found in store: ${detailsResult.exceptionOrNull()?.message}")
        }

        val storeItem = detailsResult.getOrThrow()
        val githubUrl = storeItem.githubUrl.ifBlank { storeItem.homepageUrl }

        // If no GitHub URL, return the store download error
        if (githubUrl.isBlank() || !githubUrl.contains("github.com")) {
            if (downloadResult is InstallResult.DownloadFailed) {
                return@withContext downloadResult
            }
            return@withContext InstallResult.DownloadFailed("No download source available for plugin")
        }

        // Try GitHub as fallback
        installFromGitHub(githubUrl)
    }

    /**
     * Download plugin directly from the plugin store.
     * Uses /plugin-store/:pluginId/download endpoint.
     */
    private suspend fun downloadFromStore(pluginId: String): InstallResult {
        try {
            // Get download info from store
            val downloadUrl = "$STORE_API_URL/${java.net.URLEncoder.encode(pluginId, "UTF-8")}/download"
            val infoConnection = URL(downloadUrl).openConnection() as HttpURLConnection
            infoConnection.requestMethod = "GET"
            infoConnection.setRequestProperty("Accept", "application/json")
            infoConnection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            infoConnection.connectTimeout = 10000
            infoConnection.readTimeout = 10000

            if (infoConnection.responseCode != 200) {
                val errorBody = try { infoConnection.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                return InstallResult.DownloadFailed("Store download failed: HTTP ${infoConnection.responseCode} - $errorBody")
            }

            val infoResponse = infoConnection.inputStream.bufferedReader().readText()
            val downloadInfo = json.decodeFromString<DownloadInfoResponse>(infoResponse)

            // Download the JAR from the signed URL
            pluginsDir.mkdirs()
            val jarFileName = "${pluginId.replace(".", "_")}_${downloadInfo.version}.jar"
            val destFile = File(pluginsDir, jarFileName)

            val jarConnection = URL(downloadInfo.downloadUrl).openConnection() as HttpURLConnection
            jarConnection.instanceFollowRedirects = true
            jarConnection.setRequestProperty("User-Agent", "BOSS-Plugin-Manager")
            jarConnection.connectTimeout = 30000
            jarConnection.readTimeout = 60000

            if (jarConnection.responseCode != 200) {
                return InstallResult.DownloadFailed("JAR download failed: HTTP ${jarConnection.responseCode}")
            }

            destFile.outputStream().use { output ->
                jarConnection.inputStream.copyTo(output)
            }

            // Verify SHA-256 if provided
            if (downloadInfo.sha256.isNotBlank()) {
                val actualSha256 = calculateSha256(destFile)
                if (!actualSha256.equals(downloadInfo.sha256, ignoreCase = true)) {
                    destFile.delete()
                    return InstallResult.DownloadFailed("SHA-256 verification failed")
                }
            }

            // Load the plugin via delegate
            val loadedInfo = loaderDelegate?.loadPlugin(destFile.absolutePath)
                ?: return InstallResult.LoadFailed("No plugin loader available")

            val pluginInfo = loadedInfo.toPluginInfo().copy(
                jarPath = destFile.absolutePath,
                installedAt = System.currentTimeMillis()
            )

            // Refresh installed plugins
            refreshInstalledPlugins()

            _events.emit(PluginEvent.PluginInstalled(pluginInfo))
            return InstallResult.Success(pluginInfo)

        } catch (e: Exception) {
            return InstallResult.DownloadFailed("Store download error: ${e.message}")
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

        // Uninstall current version - check result before proceeding
        val uninstallResult = uninstallPlugin(pluginId)
        if (uninstallResult is UninstallResult.CannotUnload) {
            return@withContext InstallResult.LoadFailed("Cannot update: ${uninstallResult.reason}")
        }
        if (uninstallResult is UninstallResult.Failed) {
            return@withContext InstallResult.LoadFailed("Uninstall failed: ${uninstallResult.error}")
        }

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
            // Get access token for authentication
            val accessToken = loaderDelegate?.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Not authenticated. Please sign in to delete plugins from the store."))
            }

            // Use the admin endpoint: DELETE /plugin-store/admin/:pluginId
            val url = "$STORE_API_URL/admin/${java.net.URLEncoder.encode(pluginId, "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

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
            // Get access token for authentication
            val accessToken = loaderDelegate?.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                onError("Not authenticated. Please sign in to publish plugins.")
                return@withContext
            }

            onProgress(0.1f)

            // Check if this is a GitHub URL - use simplified /github endpoint
            if (homepageUrl.contains("github.com")) {
                publishFromGitHub(
                    githubUrl = homepageUrl,
                    changelog = changelog,
                    tags = tags,
                    accessToken = accessToken,
                    onProgress = onProgress,
                    onSuccess = onSuccess,
                    onError = onError
                )
                return@withContext
            }

            // For non-GitHub sources, use multi-step publish flow
            val jarFile = File(jarPath)
            if (!jarFile.exists()) {
                onError("JAR file not found: $jarPath")
                return@withContext
            }

            onProgress(0.2f)

            // Step 1: Create or update plugin entry
            val createPluginBody = buildString {
                append("{")
                append("\"pluginId\": \"$pluginId\",")
                append("\"displayName\": \"${displayName.replace("\"", "\\\"")}\",")
                append("\"description\": \"${(description ?: "").replace("\"", "\\\"").replace("\n", "\\n")}\",")
                append("\"authorName\": \"${authorName.replace("\"", "\\\"")}\",")
                append("\"homepageUrl\": \"${homepageUrl.replace("\"", "\\\"")}\",")
                append("\"type\": \"$pluginType\",")
                append("\"apiVersion\": \"$apiVersion\"")
                if (!iconUrl.isNullOrBlank()) {
                    append(",\"iconUrl\": \"${iconUrl.replace("\"", "\\\"")}\"")
                }
                if (tags.isNotEmpty()) {
                    append(",\"tags\": [${tags.joinToString(",") { "\"$it\"" }}]")
                }
                append("}")
            }

            // Try to create plugin (will fail if already exists, which is fine)
            val createUrl = "$STORE_API_URL/publish"
            val createConn = URL(createUrl).openConnection() as HttpURLConnection
            createConn.requestMethod = "POST"
            createConn.doOutput = true
            createConn.setRequestProperty("Content-Type", "application/json")
            createConn.setRequestProperty("Accept", "application/json")
            createConn.setRequestProperty("Authorization", "Bearer $accessToken")
            createConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            createConn.connectTimeout = 30000
            createConn.readTimeout = 30000
            createConn.outputStream.bufferedWriter().use { it.write(createPluginBody) }

            // Read response (ignore 400 "already exists" error)
            val createResponseCode = createConn.responseCode
            if (createResponseCode != 200 && createResponseCode != 201 && createResponseCode != 400) {
                val errorBody = createConn.errorStream?.bufferedReader()?.readText()
                onError("Failed to create plugin: HTTP $createResponseCode - ${errorBody ?: createConn.responseMessage}")
                return@withContext
            }

            onProgress(0.3f)

            // Step 2: Create version and get upload URL
            val versionBody = buildString {
                append("{")
                append("\"version\": \"$version\",")
                append("\"changelog\": \"${(changelog ?: "").replace("\"", "\\\"").replace("\n", "\\n")}\",")
                append("\"minBossVersion\": \"$minBossVersion\",")
                append("\"dependencies\": []")
                append("}")
            }

            val versionUrl = "$STORE_API_URL/$pluginId/version"
            val versionConn = URL(versionUrl).openConnection() as HttpURLConnection
            versionConn.requestMethod = "POST"
            versionConn.doOutput = true
            versionConn.setRequestProperty("Content-Type", "application/json")
            versionConn.setRequestProperty("Accept", "application/json")
            versionConn.setRequestProperty("Authorization", "Bearer $accessToken")
            versionConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            versionConn.connectTimeout = 30000
            versionConn.readTimeout = 30000
            versionConn.outputStream.bufferedWriter().use { it.write(versionBody) }

            if (versionConn.responseCode != 200 && versionConn.responseCode != 201) {
                val errorBody = versionConn.errorStream?.bufferedReader()?.readText()
                onError("Failed to create version: HTTP ${versionConn.responseCode} - ${errorBody ?: versionConn.responseMessage}")
                return@withContext
            }

            val versionResponse = versionConn.inputStream.bufferedReader().readText()
            val uploadUrlMatch = Regex(""""uploadUrl"\s*:\s*"([^"]+)"""").find(versionResponse)
            val versionIdMatch = Regex(""""versionId"\s*:\s*"([^"]+)"""").find(versionResponse)

            val uploadUrl = uploadUrlMatch?.groupValues?.get(1)
            val versionId = versionIdMatch?.groupValues?.get(1)

            if (uploadUrl.isNullOrBlank() || versionId.isNullOrBlank()) {
                onError("Failed to get upload URL from version response")
                return@withContext
            }

            onProgress(0.5f)

            // Step 3: Upload JAR to signed URL
            val jarBytes = jarFile.readBytes()
            val uploadConn = URL(uploadUrl).openConnection() as HttpURLConnection
            uploadConn.requestMethod = "PUT"
            uploadConn.doOutput = true
            uploadConn.setRequestProperty("Content-Type", "application/octet-stream")
            uploadConn.connectTimeout = 60000
            uploadConn.readTimeout = 120000
            uploadConn.outputStream.use { it.write(jarBytes) }

            if (uploadConn.responseCode != 200) {
                onError("Failed to upload JAR: HTTP ${uploadConn.responseCode}")
                return@withContext
            }

            onProgress(0.8f)

            // Step 4: Finalize version with SHA256 and size
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(jarBytes)
                .joinToString("") { "%02x".format(it) }

            val finalizeBody = buildString {
                append("{")
                append("\"versionId\": \"$versionId\",")
                append("\"sha256\": \"$sha256\",")
                append("\"jarSize\": ${jarBytes.size}")
                append("}")
            }

            val finalizeUrl = "$STORE_API_URL/version/finalize"
            val finalizeConn = URL(finalizeUrl).openConnection() as HttpURLConnection
            finalizeConn.requestMethod = "POST"
            finalizeConn.doOutput = true
            finalizeConn.setRequestProperty("Content-Type", "application/json")
            finalizeConn.setRequestProperty("Accept", "application/json")
            finalizeConn.setRequestProperty("Authorization", "Bearer $accessToken")
            finalizeConn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            finalizeConn.connectTimeout = 30000
            finalizeConn.readTimeout = 30000
            finalizeConn.outputStream.bufferedWriter().use { it.write(finalizeBody) }

            if (finalizeConn.responseCode != 200) {
                val errorBody = finalizeConn.errorStream?.bufferedReader()?.readText()
                onError("Failed to finalize version: HTTP ${finalizeConn.responseCode} - ${errorBody ?: finalizeConn.responseMessage}")
                return@withContext
            }

            onProgress(1.0f)
            onSuccess(pluginId)

        } catch (e: Exception) {
            onError(e.message ?: "Unknown error during publish")
        }
    }

    /**
     * Publish plugin using the simplified GitHub endpoint.
     * This fetches the JAR from GitHub releases and publishes in one request.
     */
    private suspend fun publishFromGitHub(
        githubUrl: String,
        changelog: String?,
        tags: List<String>,
        accessToken: String,
        onProgress: (Float) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            onProgress(0.3f)

            // Build request body for /github endpoint
            val requestBody = buildString {
                append("{")
                append("\"githubUrl\": \"${githubUrl.replace("\"", "\\\"")}\"")
                if (!changelog.isNullOrBlank()) {
                    append(",\"changelog\": \"${changelog.replace("\"", "\\\"").replace("\n", "\\n")}\"")
                }
                if (tags.isNotEmpty()) {
                    append(",\"tags\": [${tags.joinToString(",") { "\"$it\"" }}]")
                }
                append("}")
            }

            onProgress(0.5f)

            // Send request to /github endpoint
            val url = "$STORE_API_URL/github"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            connection.connectTimeout = 120000  // Longer timeout for GitHub fetch
            connection.readTimeout = 180000

            connection.outputStream.bufferedWriter().use { it.write(requestBody) }

            onProgress(0.8f)

            if (connection.responseCode == 200 || connection.responseCode == 201) {
                val response = connection.inputStream.bufferedReader().readText()
                // Extract pluginId from response
                val pluginIdMatch = Regex(""""pluginId"\s*:\s*"([^"]+)"""").find(response)
                val pluginId = pluginIdMatch?.groupValues?.get(1) ?: "unknown"
                onProgress(1.0f)
                onSuccess(pluginId)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                onError("Publish failed: HTTP ${connection.responseCode} - ${errorBody ?: connection.responseMessage}")
            }
        } catch (e: Exception) {
            onError("GitHub publish error: ${e.message ?: "Unknown error"}")
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
