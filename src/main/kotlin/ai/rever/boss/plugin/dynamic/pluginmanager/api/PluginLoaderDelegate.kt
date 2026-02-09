package ai.rever.boss.plugin.dynamic.pluginmanager.api

/**
 * Delegate interface for plugin loading/unloading operations.
 *
 * BossConsole implements this interface and registers it via:
 * ```kotlin
 * context.registerPluginAPI(pluginLoaderDelegate)
 * ```
 *
 * The plugin-manager retrieves it via:
 * ```kotlin
 * val loader = context.getPluginAPI(PluginLoaderDelegate::class.java)
 * ```
 *
 * This allows the plugin-manager to trigger plugin load/unload operations
 * without depending on BossConsole internals.
 */
interface PluginLoaderDelegate {

    /**
     * Load a plugin from a JAR file.
     *
     * @param jarPath Absolute path to the plugin JAR
     * @return PluginInfo if successful, null if loading failed
     */
    suspend fun loadPlugin(jarPath: String): PluginInfo?

    /**
     * Unload a currently loaded plugin.
     *
     * @param pluginId The plugin ID to unload
     * @return true if successfully unloaded, false otherwise
     */
    suspend fun unloadPlugin(pluginId: String): Boolean

    /**
     * Reload a plugin (unload then load).
     *
     * @param pluginId The plugin ID to reload
     * @return PluginInfo if successful, null if reload failed
     */
    suspend fun reloadPlugin(pluginId: String): PluginInfo?

    /**
     * Get list of currently loaded plugins from the runtime.
     * This returns plugins that are actually loaded in memory,
     * which may differ from installed.json.
     */
    fun getLoadedPlugins(): List<PluginInfo>

    /**
     * Check if a plugin is currently loaded in memory.
     */
    fun isPluginLoaded(pluginId: String): Boolean

    /**
     * Get the plugins directory path.
     */
    fun getPluginsDirectory(): String

    /**
     * Get the bundled plugins directory path.
     */
    fun getBundledPluginsDirectory(): String
}
