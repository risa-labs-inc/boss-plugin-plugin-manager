package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Toolbox organisation call to action.
 *
 * Tested here rather than through the view because the decision is the part
 * that can be wrong in a way nobody notices: every outcome renders a
 * plausible-looking button, so a mistake shows up as the wrong offer rather
 * than as a broken panel.
 */
class OrganisationGateTest {
    @Test
    fun `an unknown membership renders nothing at all`() {
        // The case that matters. Treating "not asked yet" as "has none" would
        // show "Request an organisation" to every existing member for the
        // length of a round trip, every time the panel opens.
        assertNull(organisationCta(membership = null, pluginInstalled = false))
        assertNull(organisationCta(membership = null, pluginInstalled = true))
    }

    @Test
    fun `no organisation offers to request one`() {
        assertEquals(
            OrganisationCta.CREATE,
            organisationCta(Membership.NONE, pluginInstalled = false),
        )
    }

    @Test
    fun `no organisation still offers to request, even if the plugin is installed`() {
        // Installing the plugin does not make you a member of anything, so the
        // offer is unchanged.
        assertEquals(
            OrganisationCta.CREATE,
            organisationCta(Membership.NONE, pluginInstalled = true),
        )
    }

    @Test
    fun `a member without the plugin is offered the install`() {
        assertEquals(
            OrganisationCta.INSTALL_PLUGIN,
            organisationCta(Membership.ACTIVE, pluginInstalled = false),
        )
    }

    @Test
    fun `a member with the plugin is offered the panel`() {
        assertEquals(
            OrganisationCta.OPEN,
            organisationCta(Membership.ACTIVE, pluginInstalled = true),
        )
    }

    @Test
    fun `a pending creation request offers no action`() {
        assertEquals(
            OrganisationCta.REQUEST_PENDING,
            organisationCta(Membership.NONE, pluginInstalled = false, hasPendingRequest = true),
        )
        // Membership wins: someone since added to an organisation should be pointed at it,
        // not at a request that is now moot.
        assertEquals(
            OrganisationCta.OPEN,
            organisationCta(Membership.ACTIVE, pluginInstalled = true, hasPendingRequest = true),
        )
        // Rendered, but not clickable: there is nothing to do but wait, and a live button would
        // submit a duplicate that returns "a pending request already exists" and reads as a
        // failure.
        assertEquals(false, organisationCtaEnabled(OrganisationCta.REQUEST_PENDING))
        assertEquals(true, organisationCtaEnabled(OrganisationCta.CREATE))
    }

    @Test
    fun `every outcome has a label and a description`() {
        // A missing branch here would render an empty button.
        OrganisationCta.entries.forEach { cta ->
            assertTrue(organisationCtaLabel(cta).isNotBlank(), "$cta has no label")
            assertTrue(organisationCtaDescription(cta).isNotBlank(), "$cta has no description")
        }
    }

    @Test
    fun `labels name the action, and are distinct from each other`() {
        val labels = OrganisationCta.entries.map { organisationCtaLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "two outcomes share a label: $labels")
        // Each says what will happen rather than naming the noun.
        assertTrue(organisationCtaLabel(OrganisationCta.CREATE).startsWith("Request"))
        assertTrue(organisationCtaLabel(OrganisationCta.INSTALL_PLUGIN).startsWith("Install"))
        assertTrue(organisationCtaLabel(OrganisationCta.OPEN).startsWith("Open"))
    }

    @Test
    fun `the plugin id matches the plugin's own manifest`() {
        // Both the install call and the installed-list check key off this, so a
        // typo silently means "never installed" and the install button never
        // goes away.
        assertEquals("ai.rever.boss.plugin.dynamic.organisation", OrganisationPlugin.PLUGIN_ID)
        assertEquals("organisation", OrganisationPlugin.PANEL_ID)
    }

    @Test
    fun `the decision is total over its inputs`() {
        // Three states for membership, two for the plugin: no combination may
        // throw, and only the null-membership pair may be absent.
        val memberships = listOf(null, Membership.NONE, Membership.ACTIVE)
        val installed = listOf(false, true)
        for (m in memberships) {
            for (i in installed) {
                val result = organisationCta(m, i)
                if (m == null) assertNull(result) else assertNotNull(result, "m=$m i=$i")
            }
        }
    }
}

/**
 * Parsing the `get_my_organisations` body.
 *
 * The parse decides which of three offers the Toolbox makes, and every wrong
 * answer still renders a plausible button, so the failure modes are quiet.
 */
class ParseMembershipTest {
    /** What every real response contains: the seeded boss organisation, active. */
    private val bossRow = """{"slug":"boss","status":"active","is_system":true}"""

    @Test
    fun `the seeded boss org alone is NOT membership`() {
        // THE test this file was missing. The seed makes every user an active member of the boss
        // org and handle_new_user keeps every signup there, so counting it made ACTIVE the only
        // reachable answer and CREATE dead code in production. The old tests passed only because
        // they fed Membership.NONE directly, which no real response can produce.
        assertEquals(
            Membership.NONE,
            parseMembership("""{"success":true,"data":[$bossRow]}"""),
        )
    }

    @Test
    fun `a real organisation alongside the boss org IS membership`() {
        assertEquals(
            Membership.ACTIVE,
            parseMembership(
                """{"success":true,"data":[
                    $bossRow,
                    {"slug":"acme","status":"active","is_system":false}
                ]}""",
            ),
        )
    }

    @Test
    fun `a row with no status counts as active`() {
        assertEquals(
            Membership.ACTIVE,
            parseMembership("""{"success":true,"data":[{"slug":"acme","is_system":false}]}"""),
        )
    }

    @Test
    fun `a row with no is_system flag is treated as a real organisation`() {
        // Absent means false: only the seed sets it, so defaulting the other way would make
        // every organisation invisible.
        assertEquals(
            Membership.ACTIVE,
            parseMembership("""{"success":true,"data":[{"slug":"acme","status":"active"}]}"""),
        )
    }

    @Test
    fun `an empty list is NONE`() {
        assertEquals(Membership.NONE, parseMembership("""{"success":true,"data":[]}"""))
    }

    @Test
    fun `a pending or invited membership is not membership`() {
        // Both are about joining an EXISTING organisation, reviewed by that organisation's own
        // admin - a different thing from an organisation-creation request.
        assertEquals(
            Membership.NONE,
            parseMembership(
                """{"success":true,"data":[$bossRow,{"slug":"acme","status":"pending","is_system":false}]}""",
            ),
        )
        assertEquals(
            Membership.NONE,
            parseMembership(
                """{"success":true,"data":[$bossRow,{"slug":"acme","status":"invited","is_system":false}]}""",
            ),
        )
    }

    @Test
    fun `a refusal is unknown, not NONE`() {
        assertNull(parseMembership("""{"success":false,"error":"Not authenticated"}"""))
    }

    @Test
    fun `malformed and empty bodies are unknown`() {
        assertNull(parseMembership(null))
        assertNull(parseMembership(""))
        assertNull(parseMembership("   "))
        assertNull(parseMembership("not json"))
        assertNull(parseMembership("[]"))
        assertNull(parseMembership("""{"success":true}"""))
        assertNull(parseMembership("""{"success":true,"data":{"not":"a list"}}"""))
    }

    @Test
    fun `unknown fields do not break the parse`() {
        assertEquals(
            Membership.ACTIVE,
            parseMembership(
                """{"success":true,"extra":1,"data":[{"slug":"a","status":"active","is_system":false,"future":9}]}""",
            ),
        )
    }
}

/**
 * The organisation-creation request signal.
 *
 * Separate from membership because submit_organisation_request writes to
 * organisation_requests and creates no membership row - refreshing membership alone could never
 * move the call to action off CREATE.
 */
class ParsePendingRequestTest {
    @Test
    fun `a pending request is detected`() {
        assertEquals(
            true,
            parsePendingRequest("""{"success":true,"data":[{"slug":"acme","status":"pending"}]}"""),
        )
    }

    @Test
    fun `a reviewed request is not pending`() {
        for (status in listOf("approved", "rejected", "withdrawn")) {
            assertEquals(
                false,
                parsePendingRequest("""{"success":true,"data":[{"slug":"a","status":"$status"}]}"""),
                "status=$status",
            )
        }
    }

    @Test
    fun `no requests, refusals and malformed bodies are all false`() {
        assertEquals(false, parsePendingRequest("""{"success":true,"data":[]}"""))
        assertEquals(false, parsePendingRequest("""{"success":false,"error":"Permission denied"}"""))
        assertEquals(false, parsePendingRequest(null))
        assertEquals(false, parsePendingRequest("not json"))
    }
}
