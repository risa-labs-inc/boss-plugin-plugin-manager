package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * Plugin Manager component for managing plugins.
 *
 * This component displays the plugin management UI including:
 * - List of installed plugins
 * - Plugin store browser
 * - Install/uninstall actions
 *
 * ## Current Implementation
 * Phase 1: Shows a placeholder view. The actual implementation
 * will be added when PluginManagerDataProvider is available in plugin-api.
 */
class PluginManagerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val context: PluginContext
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        PluginManagerView(context)
    }
}
