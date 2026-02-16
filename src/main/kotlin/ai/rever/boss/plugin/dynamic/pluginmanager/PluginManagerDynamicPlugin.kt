package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

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
 * ## Current Implementation
 * Phase 1: Registers a placeholder panel. The actual plugin management UI
 * will be implemented when PluginManagerDataProvider is added to plugin-api.
 *
 * ## Future Enhancement
 * When PluginManagerDataProvider is available, this plugin will:
 * - Display list of installed plugins
 * - Show plugin store with available plugins
 * - Allow installing/uninstalling plugins
 * - Show plugin update notifications
 */
class PluginManagerDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.pluginmanager"
    override val displayName: String = "Plugin Manager"
    override val version: String = "1.4.19"
    override val description: String = "Core plugin for managing installed plugins and browsing the plugin store"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-plugin-manager"

    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register the Plugin Manager panel
        context.panelRegistry.registerPanel(PluginManagerPanelInfo) { ctx, panelInfo ->
            PluginManagerComponent(ctx, panelInfo, context)
        }
    }

    override fun dispose() {
        // Unregister panel when plugin is unloaded
        pluginContext?.panelRegistry?.unregisterPanel(PluginManagerPanelInfo.id)
        pluginContext = null
    }
}
