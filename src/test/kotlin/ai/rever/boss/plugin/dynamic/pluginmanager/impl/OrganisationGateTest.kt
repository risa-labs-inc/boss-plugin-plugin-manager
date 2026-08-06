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
        assertNull(organisationCta(hasOrganisation = null, pluginInstalled = false))
        assertNull(organisationCta(hasOrganisation = null, pluginInstalled = true))
    }

    @Test
    fun `no organisation offers to request one`() {
        assertEquals(
            OrganisationCta.CREATE,
            organisationCta(hasOrganisation = false, pluginInstalled = false),
        )
    }

    @Test
    fun `no organisation still offers to request, even if the plugin is installed`() {
        // Installing the plugin does not make you a member of anything, so the
        // offer is unchanged.
        assertEquals(
            OrganisationCta.CREATE,
            organisationCta(hasOrganisation = false, pluginInstalled = true),
        )
    }

    @Test
    fun `a member without the plugin is offered the install`() {
        assertEquals(
            OrganisationCta.INSTALL_PLUGIN,
            organisationCta(hasOrganisation = true, pluginInstalled = false),
        )
    }

    @Test
    fun `a member with the plugin is offered the panel`() {
        assertEquals(
            OrganisationCta.OPEN,
            organisationCta(hasOrganisation = true, pluginInstalled = true),
        )
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
        val memberships = listOf<Boolean?>(null, false, true)
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
class ParseHasOrganisationTest {
    @Test
    fun `an active membership counts`() {
        assertEquals(
            true,
            parseHasOrganisation("""{"success":true,"data":[{"slug":"acme","status":"active"}]}"""),
        )
    }

    @Test
    fun `a row with no status counts`() {
        // get_my_organisations projects status, but a future shape that omits it
        // for the common case must not read as "not a member".
        assertEquals(
            true,
            parseHasOrganisation("""{"success":true,"data":[{"slug":"acme"}]}"""),
        )
    }

    @Test
    fun `an empty list is a confident no`() {
        assertEquals(false, parseHasOrganisation("""{"success":true,"data":[]}"""))
    }

    @Test
    fun `a pending membership is not a membership`() {
        // A pending request does not make you a member. Counting it would hide
        // the create offer from someone still waiting, with no explanation.
        assertEquals(
            false,
            parseHasOrganisation("""{"success":true,"data":[{"slug":"acme","status":"pending"}]}"""),
        )
    }

    @Test
    fun `one active among several pending still counts`() {
        assertEquals(
            true,
            parseHasOrganisation(
                """{"success":true,"data":[
                    {"slug":"a","status":"pending"},
                    {"slug":"b","status":"active"}
                ]}""",
            ),
        )
    }

    @Test
    fun `a refusal is unknown, not no`() {
        // Rendering "Request an organisation" because the read was refused
        // pushes somebody toward a duplicate of one they are already in.
        assertNull(parseHasOrganisation("""{"success":false,"error":"Not authenticated"}"""))
    }

    @Test
    fun `malformed and empty bodies are unknown`() {
        assertNull(parseHasOrganisation(null))
        assertNull(parseHasOrganisation(""))
        assertNull(parseHasOrganisation("   "))
        assertNull(parseHasOrganisation("not json"))
        assertNull(parseHasOrganisation("[]"))
        assertNull(parseHasOrganisation("""{"success":true}"""))
        assertNull(parseHasOrganisation("""{"success":true,"data":{"not":"a list"}}"""))
    }

    @Test
    fun `unknown fields do not break the parse`() {
        // The database is migrated ahead of the app.
        assertEquals(
            true,
            parseHasOrganisation(
                """{"success":true,"extra":1,"data":[{"slug":"acme","status":"active","future":9}]}""",
            ),
        )
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
