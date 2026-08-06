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
    fun `a reviewer never sees someone else's request as their own`() {
        // list_organisation_requests scopes to the caller only for a NON-reviewer. For a BOSS
        // admin it returns the whole queue, so counting any pending row would tell an admin who
        // belongs to no organisation that THEIR request is under review - and disable the button,
        // locking them out of requesting one until the queue drained.
        val queue =
            """{"success":true,"is_reviewer":true,"data":[{"slug":"someone-else","status":"pending"}]}"""
        assertEquals(false, parsePendingRequest(queue))

        // The same body for a non-reviewer IS theirs.
        val mine =
            """{"success":true,"is_reviewer":false,"data":[{"slug":"mine","status":"pending"}]}"""
        assertEquals(true, parsePendingRequest(mine))
    }

    @Test
    fun `an absent is_reviewer flag is treated as not a reviewer`() {
        // The RPC always sends it, but defaulting to "reviewer" would silently disable the
        // pending state for everyone if the field were ever dropped.
        assertEquals(
            true,
            parsePendingRequest("""{"success":true,"data":[{"slug":"mine","status":"pending"}]}"""),
        )
    }

    @Test
    fun `no requests, refusals and malformed bodies are all false`() {
        assertEquals(false, parsePendingRequest("""{"success":true,"data":[]}"""))
        assertEquals(false, parsePendingRequest("""{"success":false,"error":"Permission denied"}"""))
        assertEquals(false, parsePendingRequest(null))
        assertEquals(false, parsePendingRequest("not json"))
    }
}

/**
 * Slug and name validation for the request dialog, and reading the response.
 *
 * The slug rules mirror the database CHECK. They are duplicated here only to
 * turn a round trip into a message under the field, so the two must agree -
 * a client rule stricter than the server silently forbids valid names, and one
 * looser just moves the error later.
 */
class OrganisationRequestValidationTest {
    @Test
    fun `a well-formed slug is accepted`() {
        for (slug in listOf("acme", "ac", "acme_inc", "a1", "a_1_b", "a".repeat(31))) {
            assertNull(organisationSlugError(slug), "should accept: $slug")
        }
    }

    @Test
    fun `hyphens are refused with the reason`() {
        // Role names derive from the slug and are validated without hyphens, so
        // a hyphenated slug would create an organisation whose roles could not
        // be named.
        val error = organisationSlugError("acme-inc")
        assertNotNull(error)
        assertTrue(error.contains("underscore"), "the message should say what to use instead: $error")
    }

    @Test
    fun `the length bounds match the database CHECK`() {
        assertNotNull(organisationSlugError("a"))
        assertNull(organisationSlugError("ab"))
        assertNull(organisationSlugError("a" + "b".repeat(30)))
        assertNotNull(organisationSlugError("a" + "b".repeat(31)))
    }

    @Test
    fun `a slug must start with a letter`() {
        assertNotNull(organisationSlugError("1acme"))
        assertNotNull(organisationSlugError("_acme"))
    }

    @Test
    fun `uppercase and punctuation are refused`() {
        for (slug in listOf("Acme", "acme inc", "acme.inc", "acme!", "acme/inc")) {
            assertNotNull(organisationSlugError(slug), "should refuse: $slug")
        }
    }

    @Test
    fun `an empty slug asks for one rather than complaining about the pattern`() {
        val error = organisationSlugError("")
        assertNotNull(error)
        assertTrue(error.startsWith("Enter"), "an empty field should prompt, not scold: $error")
    }

    @Test
    fun `name validation covers empty and overlong`() {
        assertNotNull(organisationNameError(""))
        assertNotNull(organisationNameError("   "))
        assertNull(organisationNameError("Acme Inc"))
        assertNull(organisationNameError("A".repeat(120)))
        assertNotNull(organisationNameError("A".repeat(121)))
    }

    @Test
    fun `a successful submission has no error`() {
        assertNull(submitRequestError("""{"success":true,"request_id":"abc"}"""))
    }

    @Test
    fun `the server's own refusal is preferred over anything invented here`() {
        // The reserved-slug and collision rules live server-side, and its
        // wording names the actual slug.
        assertEquals(
            """Slug "boss" is reserved or already in use""",
            submitRequestError("""{"success":false,"error":"Slug \"boss\" is reserved or already in use"}"""),
        )
    }

    @Test
    fun `a refusal with no message still reports a failure`() {
        assertNotNull(submitRequestError("""{"success":false}"""))
    }

    @Test
    fun `an unreachable server is an error, never a silent success`() {
        // getOrNull() yields null on a transport failure. Reading that as
        // success would close the dialog on a request that never happened.
        assertNotNull(submitRequestError(null))
        assertNotNull(submitRequestError(""))
        assertNotNull(submitRequestError("not json"))
    }
}
