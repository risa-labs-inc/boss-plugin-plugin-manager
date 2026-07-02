package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle

/**
 * Plugin Manager component for managing plugins.
 *
 * This component displays the plugin management UI including:
 * - List of installed plugins
 * - Plugin store browser
 * - Install/uninstall actions
 *
 * Created lazily when the panel is opened; the shared [PluginManagerCore]
 * (API impl, realtime, background update prompts) lives at plugin scope.
 */
class PluginManagerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val context: PluginContext,
    core: PluginManagerCore
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = PluginManagerViewModel(
        parentScope = context.pluginScope,
        core = core,
        onOpenUrl = { url ->
            // Open URL in a BOSS browser tab using ActiveTabsProvider
            val activeTabsProvider = context.activeTabsProvider
            if (activeTabsProvider != null) {
                // Extract title from URL
                val title = try {
                    java.net.URI(url).host ?: url
                } catch (e: Exception) {
                    url.take(50)
                }
                activeTabsProvider.createBrowserTab(url, title)
            } else {
                // Fallback to system browser if ActiveTabsProvider not available
                try {
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                    }
                } catch (e: Exception) {
                    // Desktop API not available or failed
                }
            }
        },
        mcpToolRegistry = context.mcpToolRegistry,
        // Resolved lazily — terminal-tab (which registers this API) loads after us.
        mcpServerControllerProvider = {
            context.getPluginAPI(ai.rever.boss.plugin.api.McpServerController::class.java)
        },
        roleManagementProvider = context.roleManagementProvider
    )

    init {
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onDestroy() {
                viewModel.dispose()
            }
        })
        // NOTE: PluginManagerAPI registration happens at plugin register() time
        // (see PluginManagerDynamicPlugin), not when the panel is first opened.
    }

    @Composable
    override fun Content() {
        PluginManagerView(viewModel)
    }
}
