package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Enough of a GitHub release to choose a jar and be allowed to download it.
 *
 * The install path falls back to a plugin's GitHub release when the store cannot answer, and that
 * fetch has always been anonymous. For the repos these plugins live in - private, by default, for
 * anything internal - `api.github.com` answers **404**, so the fallback could not succeed and said
 * so in a way that pointed at the wrong thing entirely (#52). Anonymous callers also share a
 * 60/hr rate limit by IP, which CI and a room full of BOSS instances reach together.
 */
@Serializable
data class GitHubRelease(
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    /** The API url, which is the only one an authenticated caller can use on a private repo. */
    val url: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
)

/** The jar to fetch from a release, and where to fetch it from. */
data class GitHubJar(
    val fileName: String,
    val url: String,
    /**
     * True when [url] is the asset API rather than the browser url, which needs
     * `Accept: application/octet-stream` and the token to return bytes instead of JSON.
     */
    val viaAssetApi: Boolean,
)

/**
 * Pick the jar asset out of [release].
 *
 * Addressed through the asset API when the caller [isAuthenticated], because
 * `browser_download_url` on a private repo answers 404 however good the token is - the token has
 * to travel on an `api.github.com` request. Anonymous callers keep the browser url, which is what
 * works without one and avoids sending an Accept header nothing will honour.
 *
 * Parsed rather than pattern-matched for that reason: a regex over `browser_download_url`, which
 * is what this path used, cannot see the asset url at all.
 */
fun jarAssetIn(release: GitHubRelease, isAuthenticated: Boolean): GitHubJar? {
    val asset = release.assets.firstOrNull { it.name.endsWith(".jar", ignoreCase = true) }
        ?: release.assets.firstOrNull { it.browserDownloadUrl.endsWith(".jar", ignoreCase = true) }
        ?: return null
    val fileName = asset.name.ifBlank { asset.browserDownloadUrl.substringAfterLast("/") }
    val apiUrl = asset.url.takeIf { isAuthenticated && it.isNotBlank() }
    return GitHubJar(
        fileName = fileName,
        url = apiUrl ?: asset.browserDownloadUrl.ifBlank { asset.url },
        viaAssetApi = apiUrl != null,
    )
}

/**
 * A GitHub token from the environment, or null when there is none.
 *
 * The host resolves these from rather more places - system properties, `local.properties`, the
 * `gh` CLI - in `PluginStoreSetup.applyGitHubAuth`. None of that is reachable from a plugin
 * classloader, and the API exposes no token provider, so this covers the two variables CI and
 * shells already set. Blank counts as absent: an empty `GITHUB_TOKEN` left in a shell profile
 * would otherwise send `Authorization: Bearer ` and turn a working anonymous fetch into a 401.
 */
fun githubToken(lookup: (String) -> String?): String? =
    sequenceOf("GITHUB_TOKEN", "GH_TOKEN")
        .mapNotNull { lookup(it)?.trim() }
        .firstOrNull { it.isNotEmpty() }
