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
    private fun item(pluginId: String, orgSlug: String = "boss", author: String = "Risa Labs") =
        PluginStoreItem(
            pluginId = pluginId,
            displayName = pluginId,
            author = author,
            orgSlug = orgSlug,
        )

    @Test
    fun `a plugin is indexed by its plugin id, with author and organisation together`() {
        val index = storeProvenanceByPluginId(
            listOf(item("ai.rever.a", "boss", "Risa Labs"), item("ai.rever.b", "risa", "Someone")),
        )
        // Both facts from ONE lookup. Two parallel maps invited a call site that had the author
        // and not the organisation, which is how the three tabs drifted apart.
        assertEquals("boss", index["ai.rever.a"]?.orgSlug)
        assertEquals("Risa Labs", index["ai.rever.a"]?.author)
        assertEquals("risa", index["ai.rever.b"]?.orgSlug)
        assertEquals("Someone", index["ai.rever.b"]?.author)
    }

    @Test
    fun `a plugin with an author but no organisation is still indexed`() {
        // One of the two is enough to be worth an entry. Dropping it because the organisation is
        // missing would lose the author line as well, on a card that could have shown it.
        val index = storeProvenanceByPluginId(listOf(item("ai.rever.a", orgSlug = "")))
        assertEquals("Risa Labs", index["ai.rever.a"]?.author)
        assertEquals("", index["ai.rever.a"]?.orgSlug)
    }

    @Test
    fun `a plugin with neither is omitted entirely`() {
        val index = storeProvenanceByPluginId(listOf(item("ai.rever.a", orgSlug = "", author = "")))
        assertTrue(index.isEmpty())
        assertFalse(index.containsKey("ai.rever.a"))
    }

    @Test
    fun `an empty plugin id is dropped, so it cannot become a wildcard entry`() {
        // StoreOrgRow.pluginId is nullable on the wire and coalesces to "" here. An "" key would
        // match nothing at the call site, but it would also make the map look non-empty.
        val index = storeProvenanceByPluginId(listOf(item("", "boss")))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `the first row wins for a duplicate plugin id`() {
        // plugins.plugin_id is unique, so this should be unreachable. associate would keep the
        // LAST occurrence, which would make the badge depend on the order the store happened to
        // return - a difference nothing else in the panel would explain.
        val index = storeProvenanceByPluginId(
            listOf(item("ai.rever.a", "boss"), item("ai.rever.a", "risa")),
        )
        assertEquals("boss", index["ai.rever.a"]?.orgSlug)
        assertEquals(1, index.size)
    }

    @Test
    fun `an empty catalogue yields an empty map rather than throwing`() {
        // The state before the store fetch returns, and after it fails. Both must render no badge
        // on any tab, including Installed, which is the tab the panel opens on.
        assertTrue(storeProvenanceByPluginId(emptyList()).isEmpty())
    }

    @Test
    fun `an installed plugin the store does not list resolves to no badge`() {
        // A sideloaded jar. The map is built only from the catalogue, so the lookup misses and the
        // card shows nothing - which is the informative outcome, since no organisation vouches
        // for it.
        val index = storeProvenanceByPluginId(listOf(item("ai.rever.instore", "boss")))
        assertEquals(null, index["ai.rever.sideloaded"])
    }

    // -----------------------------------------------------------------------
    // The organisation filter
    // -----------------------------------------------------------------------

    @Test
    fun `the offered organisations come from the catalogue, sorted and deduplicated`() {
        // Sorted so the chips do not reorder themselves between refreshes when the underlying
        // list order changes, which is the kind of movement nobody can explain from the UI.
        val slugs = catalogueOrgs()
        assertEquals(listOf("boss", "risa"), slugs)
    }

    private fun catalogueOrgs(): List<String> =
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

    // -----------------------------------------------------------------------
    // Searching the organisation list, which is what makes the picker scale
    // -----------------------------------------------------------------------

    private val many = listOf("acme", "boss", "risa", "risa_labs", "zenith")

    @Test
    fun `a blank query shows every organisation`() {
        // Opening the picker must show the full list, not an empty one waiting to be typed into.
        assertEquals(many, filterOrgSlugs(many, ""))
        assertEquals(many, filterOrgSlugs(many, "   "))
    }

    @Test
    fun `matching is a substring, not a prefix`() {
        // Slugs are compounds like risa_labs, and somebody types the part they remember rather
        // than the part it starts with.
        assertEquals(listOf("risa_labs"), filterOrgSlugs(many, "labs"))
        assertEquals(listOf("risa", "risa_labs"), filterOrgSlugs(many, "risa"))
    }

    @Test
    fun `a leading at sign is stripped, because that is what the control displays`() {
        // The chip and the rows read "@risa". Typing what you see has to work.
        assertEquals(listOf("risa", "risa_labs"), filterOrgSlugs(many, "@risa"))
    }

    @Test
    fun `case is folded`() {
        // Slugs are lowercase by their CHECK constraint, but nothing tells the person typing.
        assertEquals(listOf("risa", "risa_labs"), filterOrgSlugs(many, "RISA"))
        assertEquals(listOf("acme"), filterOrgSlugs(many, "AcMe"))
    }

    @Test
    fun `no match yields an empty list rather than the whole list`() {
        // Falling back to everything would make a typo look like "no filter applied", which is
        // the opposite of what was asked for.
        assertTrue(filterOrgSlugs(many, "nosuchorg").isEmpty())
    }
}
