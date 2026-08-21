package ai.rever.boss.plugin.dynamic.pluginmanager.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Information about an installed plugin.
 */
@Serializable
data class PluginInfo(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val url: String = "",
    val type: String = "panel",
    val apiVersion: String = "",
    val minBossVersion: String = "",
    val isSystemPlugin: Boolean = false,
    val canUnload: Boolean = true,
    val loadPriority: Int = 100,
    val isEnabled: Boolean = true,
    val jarPath: String = "",
    val installedAt: Long = 0L,
    val isIncompatible: Boolean = false
)

/**
 * Plugin available in the store.
 * Matches the API response format from /plugin-store/list
 */
@Serializable
data class PluginStoreItem(
    val id: String = "",
    val pluginId: String = "",
    val displayName: String = "",
    val version: String? = null,
    val latestVersion: String? = null,
    val description: String = "",
    val author: String = "",
    val url: String = "",
    val githubUrl: String = "",
    val homepageUrl: String = "",
    val downloadUrl: String = "",
    val type: String = "panel",
    val apiVersion: String = "",
    val minBossVersion: String = "",
    val downloadCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val verified: Boolean = false,
    val rating: Float = 0f,
    val ratingCount: Int = 0,
    val iconUrl: String = "",
    /** Permissions the user must hold to install/use this plugin. Empty = open. */
    val requiredPermissions: List<String> = emptyList(),
    /**
     * The organisation that owns this plugin, and its slug for display.
     *
     * NOT from the same source as the rest of this object. The store list is read from the
     * `plugins_with_latest_version` view, which does not project `org_id` - and it cannot simply
     * be added there, because that view is `security_invoker` and the Toolbox reads it as `anon`,
     * which has no SELECT grant on `organisations` at all. A join would make the whole plugin
     * list fail, not degrade. So these come from the store's own `/list` endpoint, which resolves
     * the slug server-side under service role and already returns both fields.
     *
     * Empty when that enrichment could not run, which is why it is a defaulted empty string
     * rather than a nullable: a failed lookup must render no badge, never a broken one.
     */
    val orgId: String = "",
    val orgSlug: String = ""
)

/**
 * API response wrapper for plugin list.
 */
@Serializable
data class PluginListResponse(
    val plugins: List<PluginStoreItem> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20
)

/**
 * API response for download endpoint.
 * Contains signed URL and metadata for downloading plugin JAR.
 */
@Serializable
data class DownloadInfoResponse(
    val downloadUrl: String,
    val sha256: String = "",
    // Base64 store signature over the canonical anchor pluginId|version|sha256;
    // persisted beside the JAR as a `.sig` sidecar so the host verifies it at
    // load time. Null for versions published before store signing.
    val signature: String? = null,
    val version: String = "",
    val size: Long = 0,
    val versionId: String = "",
    val minIpcVersion: String = "1.0.0",
    /**
     * The app version this build needs, from `plugin_versions.min_boss_version`.
     *
     * NULLABLE, and defaulted null rather than "1.0.0". Two separate reasons, both load-bearing:
     *
     * A default only covers an ABSENT key. `min_boss_version` is `TEXT DEFAULT '1.0.0'` with no
     * NOT NULL, so an explicit null is reachable - and a null in a non-nullable slot throws for
     * the whole object, which here would turn a missing floor into a failed install. The same trap
     * is documented on [StoreOrgRow].
     *
     * And a floor must not be invented: a made-up "1.0.0" reads as a real answer meaning "any host
     * will do", so a genuinely incompatible version would sail through the gate that exists to
     * stop it. Absent is honestly unknown, and [BossCompat] treats unknown as installable - the
     * same fail-open the IPC gate uses, and what lets this keep working against a store that does
     * not send the field at all.
     */
    val minBossVersion: String? = null,
    val requiredPermissions: List<String> = emptyList()
)

/**
 * Information about an available plugin update.
 */
@Serializable
data class UpdateInfo(
    val pluginId: String,
    val displayName: String,
    val currentVersion: String,
    val newVersion: String,
    val changelog: String = "",
    val critical: Boolean = false
)

/**
 * Result of a plugin installation attempt.
 */
sealed class InstallResult {
    data class Success(val plugin: PluginInfo) : InstallResult()
    data class AlreadyInstalled(val currentVersion: String) : InstallResult()
    data class DownloadFailed(val error: String) : InstallResult()
    data class LoadFailed(val error: String) : InstallResult()
    data class VersionConflict(val required: String, val available: String) : InstallResult()
}

/**
 * Result of a plugin uninstallation attempt.
 */
sealed class UninstallResult {
    data object Success : UninstallResult()
    data class NotFound(val pluginId: String) : UninstallResult()
    data class CannotUnload(val reason: String) : UninstallResult()
    data class Failed(val error: String) : UninstallResult()
}

/**
 * Installed plugin entry persisted in installed.json.
 */
@Serializable
data class InstalledPluginEntry(
    @SerialName("plugin_id")
    val pluginId: String,
    @SerialName("display_name")
    val displayName: String,
    val version: String,
    @SerialName("jar_path")
    val jarPath: String,
    @SerialName("installed_at")
    val installedAt: Long,
    @SerialName("github_url")
    val githubUrl: String = "",
    val enabled: Boolean = true
)

/**
 * Root structure for installed.json file.
 */
@Serializable
data class InstalledPluginsFile(
    val plugins: List<InstalledPluginEntry> = emptyList()
)

/**
 * Plugin type enum matching the Supabase schema.
 */
enum class PluginType(val value: String, val displayText: String) {
    PANEL("panel", "Panel (Sidebar)"),
    TAB("tab", "Tab (Main Area)"),
    HYBRID("hybrid", "Hybrid (Both)"),
    SERVICE("service", "Service");

    companion object {
        fun fromString(value: String): PluginType = entries.find { it.value == value } ?: PANEL
    }
}

/**
 * State for an installed plugin in the UI.
 * Matches bundled plugin-panel-manager exactly.
 */
data class InstalledPluginState(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String,
    val enabled: Boolean,
    val healthy: Boolean,
    val canUnload: Boolean,
    val jarPath: String,
    val url: String? = null,
    val requiresAdmin: Boolean = false,
    val isIncompatible: Boolean = false
)

/**
 * Data extracted from a plugin's manifest.
 * Used when extracting manifest from JAR or fetching from GitHub.
 */
data class ExtractedManifest(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val description: String,
    val author: String?,
    val url: String?,
    val apiVersion: String = "1.0",
    val minBossVersion: String = "",
    val type: PluginType = PluginType.PANEL,
    val requiredPermissions: List<String> = emptyList(),
    val definedPermissions: List<DefinedPermissionData> = emptyList()
)

/**
 * Row from the `plugins` table for Postgrest queries.
 */
@Serializable
data class PluginRow(
    val id: String,
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("homepage_url") val homepageUrl: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val type: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    val verified: Boolean = false,
    val published: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Row from the `plugins_with_latest_version` view for Postgrest queries.
 * Includes latest version info via LATERAL JOIN, eliminating N+1 queries.
 */
@Serializable
data class PluginWithVersionRow(
    val id: String,
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
    @SerialName("author_name") val authorName: String? = null,
    @SerialName("homepage_url") val homepageUrl: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val type: String? = null,
    @SerialName("api_version") val apiVersion: String? = null,
    val verified: Boolean = false,
    val published: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("latest_min_boss_version") val latestMinBossVersion: String? = null,
    @SerialName("latest_published_at") val latestPublishedAt: String? = null
)

/**
 * The two fields the store's `/list` endpoint is read for.
 *
 * Deliberately NOT a full model of that response. This is an enrichment lookup keyed by
 * `pluginId`, and declaring the twenty other fields it returns would make every future change to
 * the store's shape a change here too, for data nothing reads. `ignoreUnknownKeys` on the decoder
 * does the rest.
 */
@Serializable
data class StoreOrgRow(
    val pluginId: String? = null,
    /**
     * NULLABLE, not `String = ""`. A default only covers an ABSENT key; an explicit `null` in a
     * non-nullable slot throws, and because these arrive inside a list that would fail the whole
     * response, not one row. Null is reachable here: `plugins.org_id` is nullable, and the RPC
     * projects `orgSlug` from a subquery over it, so a plugin whose org was never resolved sends
     * both keys as null. Found by a test - the first version of this model threw on it.
     */
    val orgId: String? = null,
    val orgSlug: String? = null
)

/** Envelope of `GET /plugin-store/list`. `totalCount` decides whether another page is needed. */
@Serializable
data class StoreOrgListResponse(
    val plugins: List<StoreOrgRow> = emptyList(),
    val totalCount: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0
)

/**
 * Lightweight row for batch update checks from `plugins_with_latest_version` view.
 */
@Serializable
data class PluginUpdateRow(
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("latest_version") val latestVersion: String? = null,
    /**
     * The latest version's app floor, so an update can be judged from the same row that found it.
     *
     * The alternative was a second query per candidate, and that cost is exactly why the check
     * skipped the floor and offered updates the host would refuse to load.
     */
    @SerialName("latest_min_boss_version") val latestMinBossVersion: String? = null,
)

/**
 * Row from the `plugin_versions` table for Postgrest queries.
 */
@Serializable
data class PluginVersionRow(
    val id: String,
    @SerialName("plugin_id") val pluginUuid: String,
    val version: String,
    val changelog: String? = null,
    @SerialName("min_boss_version") val minBossVersion: String? = null,
    @SerialName("min_ipc_version") val minIpcVersion: String? = null,
    @SerialName("jar_path") val jarPath: String? = null,
    @SerialName("published_at") val publishedAt: String? = null
)

/**
 * A single published version of a plugin, with its compatibility resolved against this host.
 * Surfaced in the version-history / downgrade UI.
 *
 * TWO floors, because they fail differently and only one of them used to be here. The IPC contract
 * governs whether a plugin's out-of-process half can talk to this host; `minBossVersion` governs
 * whether `DynamicPluginLoader` will load the jar AT ALL. Carrying only the first meant a version
 * above the app floor rendered as installable, and Install then downloaded something the loader
 * refused - visible only as a plugin that did not appear.
 */
data class PluginVersionInfo(
    val version: String,
    val minIpcVersion: String,
    val changelog: String = "",
    val publishedAt: String = "",
    val compatibility: IpcCompat.Status,
    /** The app version this build declares it needs, as published to the store. */
    val minBossVersion: String = "",
) {
    /**
     * Whether THIS host meets [minBossVersion]. See [BossCompat].
     *
     * DERIVED, not a constructor parameter. As two independently defaulted fields they had to be
     * kept in step by every construction site, and the consumers disagreed about which was
     * authoritative - so setting `minBossVersion` and forgetting the verdict (easy, it was
     * defaulted) rendered a blocked version as installable. `BossCompat.status` is pure over the
     * floor and a system property, so there is nothing to store.
     */
    val bossCompatibility: BossCompat.Status
        get() = BossCompat.status(minBossVersion)
}

/**
 * Internal data class for parsing plugin.json manifest.
 */
@Serializable
internal data class PluginManifestData(
    @SerialName("pluginId")
    val pluginId: String,
    @SerialName("displayName")
    val displayName: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val url: String? = null,
    val type: String? = null,
    @SerialName("apiVersion")
    val apiVersion: String? = null,
    @SerialName("minBossVersion")
    val minBossVersion: String? = null,
    @SerialName("requiredPermissions")
    val requiredPermissions: List<String> = emptyList(),
    @SerialName("definedPermissions")
    val definedPermissions: List<DefinedPermissionData> = emptyList()
)

/** A permission a plugin introduces, parsed from the manifest's definedPermissions. */
@Serializable
data class DefinedPermissionData(
    val name: String,
    val description: String = ""
)
