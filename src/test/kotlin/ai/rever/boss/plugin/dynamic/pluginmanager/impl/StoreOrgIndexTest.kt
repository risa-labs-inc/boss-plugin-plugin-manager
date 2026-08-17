package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The map three tabs read to decide which organisation badge to draw.
 *
 * Every case here is one a compile does not catch and a screenshot would not either, because the
 * failure is a badge that is silently absent or quietly wrong.
 */
class StoreOrgIndexTest {
    private fun item(pluginId: String, orgSlug: String = "boss") =
        PluginStoreItem(pluginId = pluginId, displayName = pluginId, orgSlug = orgSlug)

    @Test
    fun `a plugin with an organisation is indexed by its plugin id`() {
        val index = storeOrgSlugsByPluginId(
            listOf(item("ai.rever.a", "boss"), item("ai.rever.b", "risa")),
        )
        assertEquals("boss", index["ai.rever.a"])
        assertEquals("risa", index["ai.rever.b"])
    }

    @Test
    fun `a plugin with no organisation is omitted, not mapped to empty`() {
        // The call sites use `index[id].orEmpty()`, so both an absent key and a mapped "" render
        // nothing - but only omission keeps the map's size meaningful as "how many are attributed".
        val index = storeOrgSlugsByPluginId(listOf(item("ai.rever.a", orgSlug = "")))
        assertTrue(index.isEmpty())
        assertFalse(index.containsKey("ai.rever.a"))
    }

    @Test
    fun `an empty plugin id is dropped, so it cannot become a wildcard entry`() {
        // StoreOrgRow.pluginId is nullable on the wire and coalesces to "" here. An "" key would
        // match nothing at the call site, but it would also make the map look non-empty.
        val index = storeOrgSlugsByPluginId(listOf(item("", "boss")))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `the first row wins for a duplicate plugin id`() {
        // plugins.plugin_id is unique, so this should be unreachable. associate would keep the
        // LAST occurrence, which would make the badge depend on the order the store happened to
        // return - a difference nothing else in the panel would explain.
        val index = storeOrgSlugsByPluginId(
            listOf(item("ai.rever.a", "boss"), item("ai.rever.a", "risa")),
        )
        assertEquals("boss", index["ai.rever.a"])
        assertEquals(1, index.size)
    }

    @Test
    fun `an empty catalogue yields an empty map rather than throwing`() {
        // The state before the store fetch returns, and after it fails. Both must render no badge
        // on any tab, including Installed, which is the tab the panel opens on.
        assertTrue(storeOrgSlugsByPluginId(emptyList()).isEmpty())
    }

    @Test
    fun `an installed plugin the store does not list resolves to no badge`() {
        // A sideloaded jar. The map is built only from the catalogue, so the lookup misses and the
        // card shows nothing - which is the informative outcome, since no organisation vouches
        // for it.
        val index = storeOrgSlugsByPluginId(listOf(item("ai.rever.instore", "boss")))
        assertEquals("", index["ai.rever.sideloaded"].orEmpty())
    }

    // -----------------------------------------------------------------------
    // The organisation filter
    // -----------------------------------------------------------------------

    @Test
    fun `the offered organisations come from the catalogue, sorted and deduplicated`() {
        // Sorted so the chips do not reorder themselves between refreshes when the underlying
        // list order changes, which is the kind of movement nobody can explain from the UI.
        val slugs = storeOrgSlugsByPluginIdFixtureOrgs()
        assertEquals(listOf("boss", "risa"), slugs)
    }

    private fun storeOrgSlugsByPluginIdFixtureOrgs(): List<String> =
        storeOrgSlugs(
            listOf(
                item("a", "risa"),
                item("b", "boss"),
                item("c", "risa"),
                item("d", ""),
            ),
        )

    @Test
    fun `no filter passes everything, including plugins with no organisation`() {
        assertTrue(matchesOrgFilter("risa", null))
        assertTrue(matchesOrgFilter(null, null))
        assertTrue(matchesOrgFilter("", null))
    }

    @Test
    fun `a set filter excludes plugins whose organisation is unknown`() {
        // With @risa selected, a sideloaded plugin the store has never heard of is not a risa
        // plugin. Passing it because its organisation is unknown would make the filter mean
        // "risa, plus anything I could not classify".
        assertFalse(matchesOrgFilter(null, "risa"))
        assertFalse(matchesOrgFilter("", "risa"))
    }

    @Test
    fun `a set filter matches only its own organisation`() {
        assertTrue(matchesOrgFilter("risa", "risa"))
        assertFalse(matchesOrgFilter("boss", "risa"))
    }

    @Test
    fun `a catalogue with one organisation offers one chip, which the header hides`() {
        // The header renders nothing below two, so this is the input that produces no control -
        // worth pinning, because the alternative is a row of chips that all select everything.
        assertEquals(listOf("boss"), storeOrgSlugs(listOf(item("a"), item("b"))))
    }

    @Test
    fun `an empty catalogue offers nothing rather than throwing`() {
        assertTrue(storeOrgSlugs(emptyList()).isEmpty())
    }
}
