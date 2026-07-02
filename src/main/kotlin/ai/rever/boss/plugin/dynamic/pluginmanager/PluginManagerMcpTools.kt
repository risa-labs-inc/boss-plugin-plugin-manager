package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI

/**
 * MCP tools contributed by the Plugin Manager: list installed plugins and
 * enable/disable them. Registered in [PluginManagerDynamicPlugin.register];
 * removed automatically on disable/unload.
 *
 * Guards: refuses to disable the terminal-tab plugin (it hosts the MCP server —
 * disabling it would kill this very tool channel), the Plugin Manager itself,
 * and any non-unloadable/system plugin.
 */
internal class PluginManagerMcpToolProvider(
    override val providerId: String,
    private val api: PluginManagerAPI,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "plugins_list",
            description = "List installed BOSS plugins (id, version, enabled, system).",
            handler = McpToolHandler {
                val plugins = api.getInstalledPlugins()
                if (plugins.isEmpty()) McpToolResult("No plugins installed.")
                else McpToolResult(plugins.joinToString("\n") { p ->
                    val flags = buildList {
                        if (p.isEnabled) add("enabled") else add("disabled")
                        if (p.isSystemPlugin) add("system")
                    }.joinToString(",")
                    "${p.pluginId}  ${p.version}  [$flags]  ${p.displayName}"
                })
            },
        ),
        McpToolDefinition(
            name = "plugin_enable",
            description = "Enable an installed plugin by id.",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val id = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                if (api.enablePlugin(id)) McpToolResult("Enabled $id.")
                else McpToolResult("Could not enable $id.", isError = true)
            },
        ),
        McpToolDefinition(
            name = "plugin_disable",
            description = "Disable an installed plugin by id (cannot disable terminal-tab, plugin-manager, or system plugins).",
            inputSchema = ID_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args ->
                val id = args.string("plugin_id")
                    ?: return@McpToolHandler McpToolResult("Missing required argument: plugin_id", isError = true)
                if (id in PROTECTED_IDS) {
                    return@McpToolHandler McpToolResult("Refusing to disable protected plugin $id.", isError = true)
                }
                val info = api.getInstalledPlugins().firstOrNull { it.pluginId == id }
                if (info != null && (info.isSystemPlugin || !info.canUnload)) {
                    return@McpToolHandler McpToolResult("Refusing to disable system/non-unloadable plugin $id.", isError = true)
                }
                if (api.disablePlugin(id)) McpToolResult("Disabled $id.")
                else McpToolResult("Could not disable $id.", isError = true)
            },
        ),
    ).onEach { it.requiresAdmin = it.name == "plugin_enable" || it.name == "plugin_disable" }

    private companion object {
        val PROTECTED_IDS = setOf(
            "ai.rever.boss.plugin.dynamic.terminaltab",
            "ai.rever.boss.plugin.dynamic.pluginmanager",
        )
        const val ID_SCHEMA =
            """{"type":"object","properties":{"plugin_id":{"type":"string","description":"Plugin id (from plugins_list)."}},"required":["plugin_id"]}"""
    }
}
