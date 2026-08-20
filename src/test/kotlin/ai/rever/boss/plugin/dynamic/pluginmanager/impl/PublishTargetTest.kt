package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which organisations the Create tab offers as publish targets.
 *
 * The list is filtered on the SERVER'S OWN `can_publish`, computed by `user_can_publish_org_plugin`
 * - the same function the publish endpoint gates on. So the property worth asserting is that this
 * never widens that answer: an organisation the server would refuse must not be offered, or the
 * user picks it and gets a 403 on something they were told they could do.
 */
class PublishTargetTest {
    private fun row(
        id: String,
        slug: String,
        canPublish: Boolean,
        status: String? = "active",
        name: String? = null,
        isSystem: Boolean? = null,
    ): String {
        val nameJson = if (name == null) "" else ""","name":"$name""""
        val statusJson = if (status == null) "" else ""","status":"$status""""
        val systemJson = if (isSystem == null) "" else ""","is_system":$isSystem"""
        return """{"id":"$id","slug":"$slug","can_publish":$canPublish$statusJson$nameJson$systemJson}"""
    }

    private fun envelope(vararg rows: String) =
        """{"success":true,"data":[${rows.joinToString(",")}]}"""

    @Test
    fun `only organisations the server says are publishable are offered`() {
        val targets = parsePublishTargets(
            envelope(
                row("id-risa", "risa", canPublish = true, name = "Risa Labs Inc"),
                row("id-other", "other", canPublish = false, name = "Other Co"),
            ),
        )
        assertEquals(1, targets.size)
        assertEquals("id-risa", targets.single().orgId)
        assertEquals("risa", targets.single().slug)
        assertEquals("Risa Labs Inc", targets.single().name)
    }

    @Test
    fun `the system organisation is offered when the server allows it`() {
        // Unlike the server's own DERIVATION, which excludes boss because it holds everyone and so
        // cannot disambiguate. Here the user picks explicitly, and publishing to the BOSS store
        // itself is a legitimate choice when the server says they may - so it stays in the list and
        // is FLAGGED, and the caller decides who is offered it.
        val targets = parsePublishTargets(
            envelope(row("id-boss", "boss", canPublish = true, name = "BOSS", isSystem = true)),
        )
        assertEquals(listOf("boss"), targets.map { it.slug })
        assertTrue(targets.single().isSystem, "the flag is what keeps it off an org publisher's picker")
    }

    @Test
    fun `an absent is_system reads as not the system organisation`() {
        // The safe direction. Defaulting to true would flag every organisation as the system one
        // and leave an org-scoped publisher with an empty picker and no Create tab - a feature
        // silently missing rather than a visible error.
        val targets = parsePublishTargets(envelope(row("id-risa", "risa", canPublish = true)))
        assertFalse(targets.single().isSystem)
    }

    // -----------------------------------------------------------------------
    // Which one the form starts on
    // -----------------------------------------------------------------------

    private fun target(id: String, slug: String, isSystem: Boolean = false) =
        PublishTarget(orgId = id, slug = slug, name = slug, isSystem = isSystem)

    @Test
    fun `a global publisher starts on the BOSS store`() {
        val targets = listOf(target("id-risa", "risa"), target("id-boss", "boss", isSystem = true))
        assertEquals("id-boss", defaultPublishTarget(targets, canPublishGlobally = true))
    }

    @Test
    fun `an org publisher never starts on the system organisation`() {
        // Reachable: can_publish is the organisation's own policy answer, so a 'members' policy on
        // @boss would put it in the list for everybody. Defaulting to it would attribute a plugin
        // to the platform's store on a form the user never touched - and then 403 on publish.
        val targets = listOf(target("id-boss", "boss", isSystem = true), target("id-risa", "risa"))
        assertEquals(null, defaultPublishTarget(targets, canPublishGlobally = false))
    }

    @Test
    fun `a sole organisation is the default for either kind of publisher`() {
        val targets = listOf(target("id-risa", "risa"))
        assertEquals("id-risa", defaultPublishTarget(targets, canPublishGlobally = false))
        assertEquals("id-risa", defaultPublishTarget(targets, canPublishGlobally = true))
    }

    @Test
    fun `several organisations and no BOSS starts unset`() {
        val targets = listOf(target("id-risa", "risa"), target("id-acme", "acme"))
        assertEquals(null, defaultPublishTarget(targets, canPublishGlobally = true))
    }

    @Test
    fun `a non-active membership is not offered`() {
        // can_publish can be true for a global admin on an organisation they have only been
        // invited to. Offering it reads as membership they do not have.
        assertTrue(
            parsePublishTargets(
                envelope(row("id-x", "x", canPublish = true, status = "pending")),
            ).isEmpty(),
        )
        assertTrue(
            parsePublishTargets(
                envelope(row("id-x", "x", canPublish = true, status = "invited")),
            ).isEmpty(),
        )
    }

    @Test
    fun `an absent status counts as active`() {
        // get_my_organisations projects status today, but a shape that omitted it for the common
        // case must not read as "not a member" and silently empty the picker.
        assertEquals(
            listOf("x"),
            parsePublishTargets(envelope(row("id-x", "x", canPublish = true, status = null)))
                .map { it.slug },
        )
    }

    @Test
    fun `a nameless organisation falls back to its slug rather than being dropped`() {
        val targets = parsePublishTargets(envelope(row("id-x", "acme", canPublish = true)))
        assertEquals("acme", targets.single().name)
    }

    @Test
    fun `a refusal offers nothing rather than throwing`() {
        // Empty means no picker and the server derives as before - the safe direction, since it
        // cannot attribute a plugin somewhere the user did not choose.
        assertTrue(parsePublishTargets("""{"success":false,"error":"Not authenticated"}""").isEmpty())
    }

    @Test
    fun `malformed or absent input offers nothing`() {
        assertTrue(parsePublishTargets(null).isEmpty())
        assertTrue(parsePublishTargets("").isEmpty())
        assertTrue(parsePublishTargets("not json").isEmpty())
        assertTrue(parsePublishTargets("""{"success":true}""").isEmpty())
    }

    @Test
    fun `a row missing its id is skipped, not offered with a blank one`() {
        // A blank orgId would be sent to the publish endpoint and rejected, turning a server-side
        // data problem into a confusing 403 on the user's screen.
        assertTrue(
            parsePublishTargets("""{"success":true,"data":[{"slug":"x","can_publish":true}]}""")
                .isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Which one starts selected
    // -----------------------------------------------------------------------
    //
    // The rule lives in the composable, so these assert the inputs it keys on rather than the
    // composable itself: that boss is identifiable by a shared constant, and that the parser
    // preserves the ordering the picker's fallback depends on.

    @Test
    fun `boss is identified by a shared constant, not a literal at each call site`() {
        // The server-side derivation and the store's own org index key on this slug. A literal in
        // two places is a literal that eventually disagrees. The publish-target default no longer
        // reads it - it keys on the row's own is_system flag - but the constant still names the one
        // organisation the org-scoped publish path may never reach.
        assertEquals("boss", SYSTEM_ORG_SLUG)
    }

    @Test
    fun `boss is offered and findable among several targets`() {
        val targets = parsePublishTargets(
            envelope(
                row("id-risa", "risa", canPublish = true, name = "Risa Labs Inc"),
                row("id-boss", SYSTEM_ORG_SLUG, canPublish = true, name = "BOSS"),
            ),
        )
        // It survives parsing. If boss were filtered out here as a system organisation - which the
        // SERVER's derivation does - a global publisher would have no default at all. WHO gets
        // offered it is decided later, by isSystem rather than by this slug: see
        // `a global publisher starts on the BOSS store` and its org-scoped twin.
        assertEquals("id-boss", targets.firstOrNull { it.slug == SYSTEM_ORG_SLUG }?.orgId)
    }

    @Test
    fun `a user who cannot publish for boss still gets their sole organisation`() {
        val targets = parsePublishTargets(
            envelope(
                row("id-boss", SYSTEM_ORG_SLUG, canPublish = false, name = "BOSS"),
                row("id-risa", "risa", canPublish = true, name = "Risa Labs Inc"),
            ),
        )
        assertEquals(null, targets.firstOrNull { it.slug == SYSTEM_ORG_SLUG })
        assertEquals("id-risa", targets.singleOrNull()?.orgId)
    }
}
