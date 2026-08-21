package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where an update is fetched from, which is not a preference: aiming it at GitHub for a plugin
 * that came from the store produces an unauthenticated API call, and for a private repo GitHub
 * answers 404 rather than 403, so the failure reads like a network fault rather than a wrong
 * decision. These pin the decision so it cannot drift back.
 */
class UpdateSourceTest {
    private fun installed(
        url: String = "",
        sourceUrl: String = "",
    ) = PluginInfo(
        pluginId = "ai.rever.boss.plugin.dynamic.fluckagent",
        displayName = "Fluck Agent",
        version = "1.0.95",
        url = url,
        sourceUrl = sourceUrl,
    )

    @Test
    fun `a store plugin whose homepage is a private repo still goes to the store`() {
        // The case that broke Fluck Agent: installed from the store (no sourceUrl), homepage is a
        // private GitHub repo. Reading the homepage as a source sent this to an API call that 404s.
        val source =
            updateSourceFor(
                installed(url = "https://github.com/risa-labs-inc/boss-plugin-fluck-agent"),
            )

        assertTrue(source is UpdateSource.Store, "a store install must ask the store, not GitHub")
    }

    @Test
    fun `a homepage is offered only as a fallback, never as the first choice`() {
        val source =
            updateSourceFor(
                installed(url = "https://github.com/risa-labs-inc/boss-plugin-codebase"),
            ) as UpdateSource.Store

        assertEquals(
            "https://github.com/risa-labs-inc/boss-plugin-codebase",
            source.fallbackUrl,
            "a plugin the store does not carry must stay updatable from its release page",
        )
    }

    @Test
    fun `a recorded install source is where the update comes from`() {
        val source =
            updateSourceFor(
                installed(
                    url = "https://example.com/some-homepage",
                    sourceUrl = "https://github.com/risa-labs-inc/boss-plugin-editor-tab",
                ),
            )

        assertEquals(
            UpdateSource.Github("https://github.com/risa-labs-inc/boss-plugin-editor-tab"),
            source,
            "provenance beats both the homepage and the store",
        )
    }

    @Test
    fun `a host too old to report provenance is treated as a store install`() {
        // sourceUrl defaults to "" on an older host, and the store is the right guess: it is where
        // all but one of a normal installation's plugins come from.
        val source = updateSourceFor(installed(url = "https://github.com/risa-labs-inc/anything"))

        assertTrue(source is UpdateSource.Store)
    }

    @Test
    fun `a non-GitHub homepage is not offered as a fallback`() {
        // "Invalid GitHub URL" from a homepage that was never a download source tells the user
        // nothing. Reporting whatever the store said is more use.
        val source = updateSourceFor(installed(url = "https://risaboss.com/plugins/atlas")) as UpdateSource.Store

        assertNull(source.fallbackUrl)
    }

    @Test
    fun `no homepage at all leaves the store as the only answer`() {
        val source = updateSourceFor(installed()) as UpdateSource.Store

        assertNull(source.fallbackUrl)
    }
}
