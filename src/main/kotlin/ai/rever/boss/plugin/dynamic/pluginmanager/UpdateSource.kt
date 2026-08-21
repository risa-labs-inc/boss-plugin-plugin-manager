package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo

/**
 * Where an update for an already-installed plugin should be fetched from.
 *
 * A type of its own, and a pure function to produce it, because getting this wrong is not
 * a visible bug - it is a 404 that looks like a network problem. The choice used to be an
 * inline `if (existing.url.isNotBlank())` that read the manifest HOMEPAGE as a download
 * source. Every plugin declares a homepage, it is nearly always the repo the plugin is
 * developed in, and for a plugin distributed through the store that repo is usually
 * private - so the condition was effectively always true, the store was never asked, and
 * the GitHub API answered 404 for every private repo. Naming the decision makes it
 * testable without a network, a host, or a Supabase client.
 */
sealed interface UpdateSource {
    /**
     * Fetch from this GitHub release, and only there.
     *
     * Reached when the host recorded that THIS copy was installed from that URL, which is
     * the one thing that makes GitHub the right answer.
     */
    data class Github(val url: String) : UpdateSource

    /**
     * Ask the store, and if it does not carry this plugin, fall back to [fallbackUrl].
     *
     * The fallback keeps a plugin that only ever existed as a GitHub release updatable. It
     * is null when there is nothing usable to fall back to, in which case the store's own
     * failure is the answer to report - a store error says something useful, whereas
     * "Invalid GitHub URL" from a homepage that was never a download source does not.
     */
    data class Store(val fallbackUrl: String?) : UpdateSource
}

/**
 * Decide where [existing]'s update comes from.
 *
 * [PluginInfo.sourceUrl] is the only field that answers the question, because it is what the
 * host recorded when it installed this copy. Blank means the store - which also covers a host
 * too old to report provenance at all, and that wants the same treatment.
 */
fun updateSourceFor(existing: PluginInfo): UpdateSource =
    if (existing.sourceUrl.isNotBlank()) {
        UpdateSource.Github(existing.sourceUrl)
    } else {
        UpdateSource.Store(
            fallbackUrl = existing.url.takeIf { it.isNotBlank() && it.contains("github.com") },
        )
    }
