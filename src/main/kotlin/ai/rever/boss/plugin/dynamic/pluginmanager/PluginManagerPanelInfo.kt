package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension

/**
 * Plugin Manager panel info for dynamic plugin.
 *
 * Displays installed plugins and plugin store in a sidebar panel.
 */
object PluginManagerPanelInfo : PanelInfo {
    override val id = PanelId("plugin-manager", 100)
    override val displayName = "Plugin Manager"
    override val icon = Icons.Outlined.Extension
    override val defaultSlotPosition = right.top.bottom
}
