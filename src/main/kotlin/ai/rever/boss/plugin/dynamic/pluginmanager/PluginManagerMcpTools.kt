package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginVersionInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.blockedReason
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.isVersionNewer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Toolbox MCP tools: live installed state, compatibility checks and store mutations.
 * Registered in [PluginManagerDynamicPlugin.register];
 * removed automatically on disable/unload.
 *
 * Guards: refuses to disable the terminal-tab plugin (it hosts the MCP server —
 * disabling it would kill this very tool channel), the Plugin Manager itself,
 * and any non-unloadable/system plugin. Updates to either MCP channel plugin
 * are also refused so the handler can finish its response.
 */
internal class PluginManagerMcpToolProvider(
    override val providerId: String,
    private val api: PluginManagerAPI,
    private val installedSnapshot: () -> List<PluginInfo> = api::getInstalledPlugins,
) : McpToolProvider {

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "plugins_list",
            description = "List installed BOSS plugins (id, version, enabled, system).",
            handler = McpToolHandler {
                val plugins = installedSnapshot()
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
        McpToolDefinition.withRbac(
            requiresAdmin = true,
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
        McpToolDefinition.withRbac(
            requiresAdmin = true,
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
                val info = installedSnapshot().firstOrNull { it.pluginId == id }
                if (info != null && (info.isSystemPlugin || !info.canUnload)) {
                    return@McpToolHandler McpToolResult("Refusing to disable system/non-unloadable plugin $id.", isError = true)
                }
                if (api.disablePlugin(id)) McpToolResult("Disabled $id.")
                else McpToolResult("Could not disable $id.", isError = true)
            },
        ),
        McpToolDefinition(
            name = "plugin_check_updates",
            description = "Check installed BOSS plugins against the latest store release. Returns JSON with installed_version, latest_version, update_available, installable and blocked_reason. Optionally filter by plugin_id (blank checks all). Rows report checked, not_in_store or lookup_failed; the call is an error only when no installed plugin could be checked. Unknown host floors fail open, as in Toolbox.",
            inputSchema = CHECK_SCHEMA,
            handler = McpToolHandler { args ->
                // Blank means "all", as the mutation tools treat a blank id as absent.
                val id = args.string("plugin_id")?.takeIf { it.isNotBlank() }
                val installed = installedSnapshot().filter { id == null || it.pluginId == id }
                if (id != null && installed.isEmpty()) return@McpToolHandler error(id, "Plugin not installed: $id")
                val batch = api.fetchLatestStoreVersions(installed.map { it.pluginId })
                var checked = 0
                val rows = installed.map { plugin ->
                    val lookup = batch.getOrNull()?.get(plugin.pluginId)
                    val target = lookup?.getOrNull()
                    val reason = target?.blockedReason()
                    val status = when {
                        target != null -> "checked"
                        batch.isFailure || lookup?.isFailure == true -> "lookup_failed"
                        else -> "not_in_store"
                    }
                    if (target != null) checked++
                    buildJsonObject {
                        put("plugin_id", plugin.pluginId)
                        put("installed_version", plugin.version)
                        put("status", status)
                        put("latest_version", target?.version?.let(::JsonPrimitive) ?: JsonNull)
                        put("update_available", target?.let { JsonPrimitive(isVersionNewer(it.version, plugin.version)) } ?: JsonNull)
                        put("installable", target?.let { JsonPrimitive(reason == null) } ?: JsonNull)
                        put("blocked_reason", reason?.let(::JsonPrimitive) ?: JsonNull)
                        if (status == "lookup_failed") {
                            put("error", (batch.exceptionOrNull() ?: lookup?.exceptionOrNull())?.message ?: "Store lookup failed")
                        }
                    }
                }
                McpToolResult(buildJsonObject { put("plugins", JsonArray(rows)) }.toString(),
                    isError = installed.isNotEmpty() && checked == 0)
            },
        ),
        mutationTool("plugin_update", update = true),
        mutationTool("plugin_install", update = false),
    )

    /** The resolved store version plus every published version, for a loadable-version hint. */
    private suspend fun target(id: String, version: String?): Result<Pair<PluginVersionInfo, List<PluginVersionInfo>>> {
        val resolved = if (version == null) {
            val details = api.fetchPluginDetails(id).getOrElse { return Result.failure(it) }
            details.latestVersion ?: details.version
                ?: return Result.failure(NoSuchStoreVersion("No published version for $id"))
        } else version
        val versions = api.fetchPluginVersions(id).getOrElse { return Result.failure(it) }
        return versions.firstOrNull { it.version == resolved }?.let { Result.success(it to versions) }
            ?: Result.failure(NoSuchStoreVersion("Store version not found: $id $resolved"))
    }

    /** The store answered, and the requested version does not exist: a refusal, not a failure. */
    private class NoSuchStoreVersion(message: String) : IllegalArgumentException(message)

    /** Newest version this host can load, above [installed] when given; null when there is none. */
    private fun newestLoadable(versions: List<PluginVersionInfo>, installed: String?): String? =
        versions.filter { it.blockedReason() == null && (installed == null || isVersionNewer(it.version, installed)) }
            .map { it.version }
            .reduceOrNull { best, v -> if (isVersionNewer(v, best)) v else best }

    private fun mutationTool(name: String, update: Boolean) = McpToolDefinition.withRbac(
        name = name,
        description = if (update)
            "Update an installed BOSS plugin from the store to latest or an explicit version (including downgrade). Uses Toolbox's version installer: host compatibility gates, download integrity and host signature verification, persistence and live load. Locked plugins are staged for restart. Refuses terminal-tab and Toolbox to preserve the active MCP call; update those in the UI. Explicit same-version requests report already_installed. Without version, a latest release this host cannot load is refused and the reason names the newest loadable version, which can be passed as version. Returns JSON status, from_version, to_version and needs_restart; errors report refused (policy), failed (store or installer, may be retried) or busy (another installer holds the lease; retry later). Admin only."
        else
            "Install a BOSS store plugin that is not installed, at latest or an explicit version. Uses Toolbox's installer and host loader, including dependency handling, compatibility gates and signature verification. Without version, a latest release this host cannot load is refused and the reason names the newest loadable version, which can be passed as version. Returns JSON outcome; errors report refused, failed or busy as for plugin_update. Admin only; use plugin_update for installed plugins.",
        inputSchema = VERSION_SCHEMA,
        readOnly = false,
        requiresAdmin = true,
        handler = McpToolHandler { args ->
            val id = args.string("plugin_id")?.takeIf { it.isNotBlank() }
                ?: return@McpToolHandler error(null, "Missing required argument: plugin_id")
            if (update && id in PROTECTED_IDS) {
                return@McpToolHandler error(id, "Refusing to update MCP channel plugin $id during a tool call; use Toolbox UI")
            }
            val version = args.string("version")
            if (version != null && version.isBlank()) return@McpToolHandler error(id, "version must not be blank")
            val existing = installedSnapshot().firstOrNull { it.pluginId == id }
            if (update && existing == null) return@McpToolHandler error(id, "Plugin not installed: $id")
            if (!update && existing != null) return@McpToolHandler error(id, "Plugin already installed; use plugin_update")
            val (target, versions) = target(id, version).getOrElse {
                return@McpToolHandler error(id, it.message ?: "Store lookup failed",
                    if (it is NoSuchStoreVersion) "refused" else "failed")
            }
            if (existing != null && (target.version == existing.version ||
                    (version == null && !isVersionNewer(target.version, existing.version)))) {
                return@McpToolHandler outcome(id, if (version == null) "already_latest" else "already_installed", existing.version, existing.version, false)
            }
            target.blockedReason()?.let { reason ->
                // MCP callers cannot see the UI's "needs newer BOSS" notice, so name an alternative.
                val hint = if (version == null) newestLoadable(versions, existing?.version)
                    ?.let { "; newest version loadable here is $it (pass it as version)" }.orEmpty() else ""
                return@McpToolHandler error(id, reason + hint)
            }
            // Pin the resolved store version. This is the UI version picker's shared path,
            // so a GitHub-origin install cannot redirect an MCP store update to GitHub.
            val result = api.installStoreVersion(id, target.version, update, allowDowngrade = version != null)
            when (result) {
                is InstallResult.Success -> outcome(id, if (update) "updated" else "installed",
                    existing?.version, result.plugin.version,
                    result.needsRestart)
                is InstallResult.AlreadyInstalled -> outcome(id, if (version == null) "already_latest" else "already_installed", result.currentVersion, result.currentVersion, false)
                is InstallResult.DownloadFailed -> error(id, result.error, if (result.wasBusy()) "busy" else "failed")
                is InstallResult.LoadFailed -> error(id, result.error, "failed")
                is InstallResult.VersionConflict -> error(id, "Requires ${result.required}; available ${result.available}", "failed")
            }
        },
    )

    private fun outcome(id: String, status: String, from: String?, to: String, restart: Boolean) = McpToolResult(
        buildJsonObject {
            put("plugin_id", id)
            put("status", status)
            put("from_version", from?.let(::JsonPrimitive) ?: JsonNull)
            put("to_version", to)
            put("needs_restart", restart)
        }.toString()
    )

    /**
     * [status] separates "will never work" from "try again": `refused` for policy (floors,
     * protected IDs, wrong intent), `failed` for store lookups and installer outcomes (network,
     * integrity, signature, load), `busy` when another installer holds the update lease.
     */
    private fun error(id: String?, reason: String, status: String = "refused") = McpToolResult(buildJsonObject {
        put("plugin_id", id?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status)
        put("reason", reason)
    }.toString(), isError = true)

    private companion object {
        const val CHECK_SCHEMA = """{"type":"object","properties":{"plugin_id":{"type":"string","description":"Optional installed plugin id; omit to check all installed plugins."}}}"""
        const val VERSION_SCHEMA = """{"type":"object","properties":{"plugin_id":{"type":"string","minLength":1},"version":{"type":"string","minLength":1,"description":"Published store version; omit for latest."}},"required":["plugin_id"]}"""
        val PROTECTED_IDS = setOf(
            "ai.rever.boss.plugin.dynamic.terminaltab",
            "ai.rever.boss.plugin.dynamic.pluginmanager",
        )
        const val ID_SCHEMA =
            """{"type":"object","properties":{"plugin_id":{"type":"string","description":"Plugin id (from plugins_list)."}},"required":["plugin_id"]}"""
    }
}
