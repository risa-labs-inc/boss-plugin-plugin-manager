package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.RegisteredSurface
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.resolveLaunchSurface
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.supportsOpenPanelAsTab

/**
 * Plugin Manager dynamic plugin - Core bundled plugin.
 *
 * This is a system/bundled plugin that provides the Plugin Manager panel.
 * It allows users to view installed plugins, browse the plugin store,
 * and install/uninstall plugins.
 *
 * NOTE: This plugin has systemPlugin=true and loadPriority=5 in its manifest,
 * meaning it loads early and cannot be unloaded.
 *
 * Registration creates a [PluginManagerCore] that runs for the plugin's whole
 * lifetime: it owns the store API + realtime connection, registers the
 * [ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI] for other
 * plugins immediately, and proactively prompts (via host toasts) when
 * compatible plugin updates are available — even if the panel is never opened.
 */
class PluginManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = PluginManagerCore.PLUGIN_ID
    override val displayName: String = "Toolbox"
    override val version: String = "1.4.24"
    override val description: String = "Core plugin for managing installed plugins and browsing the plugin store"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-plugin-manager"

    private var pluginContext: PluginContext? = null
    private var core: PluginManagerCore? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        val loaderDelegate = context.getPluginAPI(PluginLoaderDelegate::class.java)
        val core = PluginManagerCore(context, loaderDelegate)
        this.core = core

        // Register the PluginManagerAPI for other plugins right away (it used
        // to happen only once the panel was first opened)
        context.registerPluginAPI(core.api)

        // Contribute plugins_list/enable/disable MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(PluginManagerMcpToolProvider(pluginId, core.api))

        // Start realtime + background update prompts
        core.start()

        // Bottom-bar download/update progress. Guarded: hosts/API layers older
        // than the status-bar registry throw here (missing class/method) — the
        // widget is simply skipped and everything else keeps working.
        runCatching {
            context.registerStatusBarItem(DownloadStatusBarItem(core.apiImpl.downloadTracker))
        }

        // Register the Plugin Manager panel
        context.panelRegistry.registerPanel(PluginManagerPanelInfo) { ctx, panelInfo ->
            PluginManagerComponent(ctx, panelInfo, context, core)
        }

        // boss://plugin?id=<this plugin>&action=install|open&plugin=<id>, which is what the Open
        // and Install buttons on a plugin's web page press. Guarded like the status-bar item: a
        // host older than the deep-link action registry throws here and everything else still
        // works.
        runCatching {
            context.registerDeepLinkActionHandler(
                PluginDeepLinkActions(
                    handlerId = pluginId,
                    api = core.api,
                    notifications = context.notificationProvider,
                    scope = context.pluginScope,
                    revealPlugin = { id -> revealInstalledPlugin(context, core.api, id) },
                    // The impl, not the interface: refreshing is how the handler stops depending
                    // on whether the panel has ever been opened.
                    refreshInstalled = { core.apiImpl.refreshInstalledPlugins() },
                ),
            )
        }
    }

    /**
     * Reveal an installed plugin's own panel, for `action=open`.
     *
     * A cut-down twin of the view model's openPlugin, and deliberately not a call into it: that
     * one needs a window id and the split operations a PANEL was constructed with, and a deep link
     * arrives with neither - typically with nothing of this plugin on screen at all.
     * `openPanelAsTab` is the one route that needs no window, so this takes it or does nothing and
     * lets the caller say so.
     */
    private fun revealInstalledPlugin(
        context: PluginContext,
        api: ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI,
        pluginId: String,
    ): Boolean =
        runCatching {
            val ops = context.splitViewOperations ?: return@runCatching false
            if (!supportsOpenPanelAsTab(ops)) return@runCatching false
            val name = api.getInstalledPlugin(pluginId)?.displayName ?: pluginId
            val panels = context.panelRegistry.getAllPanels()
            val panel =
                resolveLaunchSurface(pluginId, name, panels) {
                    RegisteredSurface(it.id.panelId, it.id.pluginId, it.displayName)
                } ?: return@runCatching false
            // resolveLaunchSurface hands back the registered panel itself, so its own PanelId goes
            // straight through. Rebuilding one from the string would drop the order field the
            // registry keys on - the trap PanelId.defaultOrder is already known for.
            ops.openPanelAsTab(panel.id)
            true
        }.getOrDefault(false)

    override fun dispose() {
        core?.dispose()
        core = null
        // The host also auto-unregisters status-bar items on unload; same
        // guard as registration for pre-status-bar API layers.
        runCatching { pluginContext?.unregisterStatusBarItem(DownloadStatusBarItem.ITEM_ID) }
        // Unregister panel when plugin is unloaded
        pluginContext?.panelRegistry?.unregisterPanel(PluginManagerPanelInfo.id)
        pluginContext = null
    }
}
