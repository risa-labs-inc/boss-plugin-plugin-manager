package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginLoaderDelegate
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

    // Try to get the loader delegate from PluginContext
    // This uses reflection to call getPluginAPI if available (plugin-api >= 1.0.15)
    private val loaderDelegate: PluginLoaderDelegate? = tryGetPluginAPI()

    private val viewModel = PluginManagerViewModel(
        scope = context.pluginScope,
        loaderDelegate = loaderDelegate
    )

    init {
        lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onDestroy() {
                viewModel.dispose()
            }
        })

        // Try to register the PluginManagerAPI for other plugins to use
        tryRegisterPluginAPI(viewModel.getAPI())
    }

    @Composable
    override fun Content() {
        PluginManagerView(viewModel)
    }

    /**
     * Try to get PluginLoaderDelegate using reflection.
     * Returns null if getPluginAPI is not available (older plugin-api versions).
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryGetPluginAPI(): PluginLoaderDelegate? {
        return try {
            val method = context::class.java.getMethod("getPluginAPI", Class::class.java)
            method.invoke(context, PluginLoaderDelegate::class.java) as? PluginLoaderDelegate
        } catch (e: Exception) {
            // getPluginAPI not available in this version of plugin-api
            null
        }
    }

    /**
     * Try to register an API using reflection.
     * Does nothing if registerPluginAPI is not available (older plugin-api versions).
     */
    private fun tryRegisterPluginAPI(api: Any) {
        try {
            val method = context::class.java.getMethod("registerPluginAPI", Any::class.java)
            method.invoke(context, api)
        } catch (e: Exception) {
            // registerPluginAPI not available in this version of plugin-api
        }
    }
}
