package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
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
    ): String {
        val nameJson = if (name == null) "" else ""","name":"$name""""
        val statusJson = if (status == null) "" else ""","status":"$status""""
        return """{"id":"$id","slug":"$slug","can_publish":$canPublish$statusJson$nameJson}"""
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
        // itself is a legitimate choice when the server says they may.
        val targets = parsePublishTargets(
            envelope(row("id-boss", "boss", canPublish = true, name = "BOSS")),
        )
        assertEquals(listOf("boss"), targets.map { it.slug })
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
}
