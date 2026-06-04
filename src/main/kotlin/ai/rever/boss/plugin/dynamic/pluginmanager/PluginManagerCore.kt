package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import ai.rever.boss.plugin.dynamic.pluginmanager.realtime.StoreChangeEvent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Plugin-scoped core that lives from `register()` to `dispose()` — independent
 * of the panel UI, which is created lazily and may be opened/closed repeatedly.
 *
 * Owns the single [PluginManagerAPIImpl] (and with it the Supabase realtime
 * connection) plus the background [UpdatePromptService], so update detection
 * and prompts work even when the Plugin Manager panel has never been opened.
 */
@OptIn(FlowPreview::class)
class PluginManagerCore(
    context: PluginContext,
    val loaderDelegate: PluginLoaderDelegate?
) {
    private val scope = context.pluginScope

    val apiImpl = PluginManagerAPIImpl(scope, loaderDelegate)
    val api: PluginManagerAPI get() = apiImpl

    private val promptService = UpdatePromptService(
        scope = scope,
        apiImpl = apiImpl,
        loaderDelegate = loaderDelegate,
        notifications = context.notificationProvider,
        storage = context.pluginStorageFactory?.createStorage(PLUGIN_ID)
    )

    companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.pluginmanager"

        /** Delay before the startup update check, letting the host finish
         * loading plugins and publish `boss.ipc.version`. */
        private const val STARTUP_CHECK_DELAY_MS = 2_000L
    }

    /** Start realtime + background update detection. Called once from `register()`. */
    fun start() {
        apiImpl.connectRealtime()

        // Startup check
        scope.launch {
            delay(STARTUP_CHECK_DELAY_MS)
            runCatching {
                apiImpl.refreshInstalledPlugins()
                promptService.checkAndPrompt()
            }
        }

        // Re-check whenever a new version is published (debounced; the prompt
        // service dedupes per version, so extra triggers are harmless)
        scope.launch {
            apiImpl.storeChanges
                .filterIsInstance<StoreChangeEvent.VersionAdded>()
                .debounce(500)
                .collect {
                    runCatching {
                        apiImpl.refreshInstalledPlugins()
                        promptService.checkAndPrompt()
                    }
                }
        }
    }

    /**
     * Tear down on plugin unload. The host cancels `pluginScope`, which stops
     * the collectors above; the realtime client owns its own internal scope,
     * so it must be disposed explicitly.
     */
    fun dispose() {
        apiImpl.realtimeClient.dispose()
    }
}
