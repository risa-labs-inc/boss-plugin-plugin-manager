package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLoaderDelegate
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
 */
class PluginManagerComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val context: PluginContext
) : PanelComponentWithUI, ComponentContext by ctx {

    // Get the loader delegate from PluginContext via getPluginAPI
    // Call directly (no reflection needed) - method is defined in PluginContext interface
    private val loaderDelegate: PluginLoaderDelegate? = context.getPluginAPI(PluginLoaderDelegate::class.java)

    private val viewModel = PluginManagerViewModel(
        scope = context.pluginScope,
        loaderDelegate = loaderDelegate,
        onOpenUrl = { url ->
            // Open URL in system browser using Java Desktop API
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                }
            } catch (e: Exception) {
                // Desktop API not available or failed
            }
        }
    )

    init {
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onDestroy() {
                viewModel.dispose()
            }
        })

        // Register the PluginManagerAPI for other plugins to use
        context.registerPluginAPI(viewModel.getAPI())
    }

    @Composable
    override fun Content() {
        PluginManagerView(viewModel)
    }
}
