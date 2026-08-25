package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import com.risaboss.toolbox.downloadcenter.HostDownloadCenter
import com.risaboss.toolbox.downloadcenter.TransferReporter
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

    /**
     * Where downloads are reported: the host's download center where there is one,
     * this plugin's own status-bar widget where there is not.
     *
     * Reached through [HostDownloadCenter] rather than by reading the context here,
     * and that indirection is load-bearing - see its KDoc. Touching
     * `context.downloadCenterProvider` from this class would make the whole plugin
     * unloadable on every host that predates the property, because the host's
     * validator rejects a plugin whose contract classes name a member it cannot
     * resolve. The fallback below is what an older host gets.
     */
    private val localTracker = DownloadProgressTracker()

    // Guarded HERE as well as inside createOrNull, and both layers are load-bearing.
    // The inner catch covers the property read; this one covers resolving and
    // verifying `createOrNull` itself, which touches the api types in its signature
    // and descriptor - a LinkageError from that is thrown at THIS call site, before
    // any code inside the method runs, so a catch in there could never see it.
    private val hostReporter =
        runCatching { HostDownloadCenter.createOrNull(context, scope) }.getOrNull()

    val reporter: TransferReporter = hostReporter ?: LocalTransferReporter(localTracker, scope)

    /**
     * The widget to register when there is no host center, or null when the host
     * renders the bar itself. Two bars for one download would be the alternative.
     */
    val statusBarItem: DownloadStatusBarItem? =
        if (hostReporter == null) DownloadStatusBarItem(localTracker) else null

    val apiImpl = PluginManagerAPIImpl(scope, loaderDelegate, reporter)
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

        // Retire an update prompt whose update has happened by some other route -
        // the Toolbox panel, another window, or the host's own prompt.
        promptService.watchForApplied()

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
