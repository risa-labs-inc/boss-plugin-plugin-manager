package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether a failed store download leaves room to try GitHub, and what to report if it does not.
 *
 * Install used to fall back to an unauthenticated GitHub release fetch on *any* store failure.
 * Two things were wrong with that, and both are about a refusal being an answer:
 *
 * - The store's 403 names the permission the caller is missing and how to get it. GitHub's 404
 *   for a private repo - which is what an unauthenticated API call returns for every internal
 *   plugin - replaced that with something nobody can act on. An operator spent an hour on the
 *   wrong problem (#52).
 * - The store refuses installs on purpose: a missing permission, a version this host's IPC
 *   cannot speak, a yanked release. Downloading the jar from GitHub instead is the gate not
 *   being a gate.
 *
 * A pure function, and tested as one, for the same reason [updateSourceFor] is: getting it
 * wrong produces a plausible-looking error rather than a visible bug.
 */
fun mayFallBackToGitHub(storeStatus: Int?): Boolean =
    // 4xx is the store answering. Anything else - 5xx, or null for a connection that never got
    // a status at all - is the store failing to answer, which is the outage the fallback is for.
    storeStatus == null || storeStatus !in 400..499

/**
 * What to tell the user when the store failed *and* the GitHub fallback failed too.
 *
 * The store's error leads, because the store is where the plugin actually comes from and its
 * message is the one that names a cause. GitHub's is kept behind it rather than dropped: when
 * the store is down, "the fallback did not work either, and here is why" is the difference
 * between one failed install and two silent ones.
 */
fun installFailureMessage(storeError: String, githubError: String): String = when {
    storeError.isBlank() -> githubError
    githubError.isBlank() -> storeError
    else -> "$storeError (GitHub fallback also failed: $githubError)"
}

/**
 * Turn a store answer, and a GitHub fallback that may or may not be allowed to run, into the
 * outcome the user sees.
 *
 * The whole decision lives here rather than inline in the install path so it can be exercised
 * without a network, a store or a host - the previous version could only be checked by
 * publishing a plugin, revoking a permission and watching what the Toolbox said.
 *
 * [tryGitHub] is called only when the fallback is allowed, and returns null when there is no
 * usable GitHub source for this plugin. A store failure the fallback is not allowed to answer
 * comes straight back, unchanged.
 */
suspend fun installOutcome(
    storeResult: InstallResult,
    storeStatus: Int?,
    isCancelled: (InstallResult) -> Boolean,
    tryGitHub: suspend () -> InstallResult?,
): InstallResult {
    if (storeResult is InstallResult.Success || isCancelled(storeResult)) return storeResult

    // A jar that downloaded and then refused to load is not a download problem, and fetching
    // the same jar from GitHub would fail the same way after a second download.
    if (storeResult !is InstallResult.DownloadFailed) return storeResult

    if (!mayFallBackToGitHub(storeStatus)) return storeResult

    val github = tryGitHub() ?: return storeResult
    if (github is InstallResult.Success || isCancelled(github)) return github

    val githubError = (github as? InstallResult.DownloadFailed)?.error
        ?: (github as? InstallResult.LoadFailed)?.error
        ?: ""
    return if (storeResult.error.isBlank()) {
        github
    } else {
        InstallResult.DownloadFailed(installFailureMessage(storeResult.error, githubError))
    }
}

/** A GitHub release, as much of one as choosing a jar to download requires. */
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
 * `browser_download_url` on a private repo answers 404 however good the token is - the token
 * has to travel on an api.github.com request. Anonymous callers keep the browser url, which is
 * what works without one and avoids sending an Accept header nothing will honour.
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
 * shells already set. Without one, a private repo's release is a 404 by construction.
 */
fun githubToken(lookup: (String) -> String?): String? =
    sequenceOf("GITHUB_TOKEN", "GH_TOKEN")
        .mapNotNull { lookup(it)?.trim() }
        .firstOrNull { it.isNotEmpty() }
