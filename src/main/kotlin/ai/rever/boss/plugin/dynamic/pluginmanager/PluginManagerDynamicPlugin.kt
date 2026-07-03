package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate

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

        // Register the Plugin Manager panel
        context.panelRegistry.registerPanel(PluginManagerPanelInfo) { ctx, panelInfo ->
            PluginManagerComponent(ctx, panelInfo, context, core)
        }
    }

    override fun dispose() {
        core?.dispose()
        core = null
        // Unregister panel when plugin is unloaded
        pluginContext?.panelRegistry?.unregisterPanel(PluginManagerPanelInfo.id)
        pluginContext = null
    }
}
