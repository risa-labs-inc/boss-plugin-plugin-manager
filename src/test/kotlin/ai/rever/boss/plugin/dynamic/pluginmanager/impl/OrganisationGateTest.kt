package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Toolbox organisation section: the member route, and whether requesting is on offer.
 *
 * Tested here rather than through the view because the decision is the part that can be wrong in a
 * way nobody notices: every outcome renders a plausible-looking button, so a mistake shows up as
 * the wrong offer rather than as a broken panel.
 *
 * TWO axes, deliberately. They were one four-outcome enum, and collapsing them is what made the
 * request form unreachable for anybody who already belonged to an organisation - see
 * `a member can still request another organisation`, which is the regression these tests exist for.
 */
class OrganisationGateTest {
    @Test
    fun `an unknown membership offers no member route`() {
        // Treating "not asked yet" as "has none" would be an assertion we cannot back. It renders
        // no access button either way - the difference is expressed by orgRequestState.
        assertNull(orgAccessRoute(membership = null, pluginInstalled = false))
        assertNull(orgAccessRoute(membership = null, pluginInstalled = true))
    }

    @Test
    fun `belonging to nothing offers no member route, plugin or not`() {
        // Installing the plugin does not make you a member of anything.
        assertNull(orgAccessRoute(Membership.NONE, pluginInstalled = false))
        assertNull(orgAccessRoute(Membership.NONE, pluginInstalled = true))
    }

    @Test
    fun `a member without the plugin is offered the install`() {
        assertEquals(
            OrgAccess.INSTALL_PLUGIN,
            orgAccessRoute(Membership.ACTIVE, pluginInstalled = false),
        )
    }

    @Test
    fun `a member with the plugin is offered the panel`() {
        assertEquals(
            OrgAccess.OPEN,
            orgAccessRoute(Membership.ACTIVE, pluginInstalled = true),
        )
    }

    @Test
    fun `belonging to nothing offers the request`() {
        assertEquals(
            OrgRequest.AVAILABLE,
            orgRequestState(
                Membership.NONE,
                hasPendingRequest = false,
                providerAvailable = true,
                readCompleted = true,
            ),
        )
    }

    @Test
    fun `a member can still request another organisation`() {
        // THE REGRESSION. ACTIVE membership used to collapse to OPEN / INSTALL_PLUGIN, which meant
        // the request form did not exist for a member - and since the Create tab was revealed only
        // for the request branches, a member got no Create tab at all. Every user is seeded into
        // an organisation of some size, so that was nearly everybody.
        assertEquals(
            OrgRequest.AVAILABLE,
            orgRequestState(
                Membership.ACTIVE,
                hasPendingRequest = false,
                providerAvailable = true,
                readCompleted = true,
            ),
        )
    }

    @Test
    fun `a pending request suppresses the offer in every membership state`() {
        // Including ACTIVE. A member who has already asked must not be handed the button again:
        // resubmitting returns "a pending request already exists", which reads as a failure.
        listOf(null, Membership.NONE, Membership.ACTIVE).forEach { m ->
            assertEquals(
                OrgRequest.PENDING,
                orgRequestState(m, hasPendingRequest = true, providerAvailable = true, readCompleted = true),
                "membership=$m",
            )
        }
        // Rendered, but not clickable: there is nothing to do but wait.
        assertFalse(orgRequestEnabled(OrgRequest.PENDING))
        assertTrue(orgRequestEnabled(OrgRequest.AVAILABLE))
    }

    @Test
    fun `a read that answered nothing still offers the request`() {
        // A transport failure, a refusal envelope and malformed JSON all leave membership null,
        // and none of them resolve by waiting. Refusing to offer the request there left a
        // permanent "Checking your organisations..." with no way forward - the exact shape of the
        // bug this change is about, reintroduced one level down. The server validates the
        // submission regardless, so offering it costs nothing.
        assertEquals(
            OrgRequest.AVAILABLE,
            orgRequestState(
                null,
                hasPendingRequest = false,
                providerAvailable = true,
                readCompleted = true,
            ),
        )
    }

    @Test
    fun `wording never asserts a membership we could not read`() {
        // AVAILABLE is now reachable with membership unknown, and the non-member sentence would
        // be an assertion we cannot back. Three variants, all distinct.
        val unknown = orgRequestDescription(OrgRequest.AVAILABLE, null)
        assertFalse(unknown.contains("not a member"))
        assertFalse(unknown.contains("another organisation"))
        val all = listOf(null, Membership.NONE, Membership.ACTIVE)
            .map { orgRequestDescription(OrgRequest.AVAILABLE, it) }
        assertEquals(all.size, all.toSet().size, "two membership states share wording: $all")
    }

    @Test
    fun `an unresolved membership is UNKNOWN only while the read is outstanding`() {
        // Distinct from AVAILABLE on purpose: the button is disabled and the label is neutral, so
        // a member is never flashed "Request an organisation" as if we had established they are in
        // none. Distinct from UNAVAILABLE too - this one resolves on the next refresh.
        assertEquals(
            OrgRequest.UNKNOWN,
            orgRequestState(null, hasPendingRequest = false, providerAvailable = true, readCompleted = false),
        )
        assertFalse(orgRequestEnabled(OrgRequest.UNKNOWN))
    }

    @Test
    fun `no provider is UNAVAILABLE, whatever else is known`() {
        // The state that used to be indistinguishable from UNKNOWN, and the reason it needed its
        // own name: waiting never helps, so "Checking your organisations..." would sit there
        // forever. It outranks a pending request too - nothing can be submitted from here either
        // way, and there is no point implying a queue we cannot read.
        listOf(null, Membership.NONE, Membership.ACTIVE).forEach { m ->
            listOf(false, true).forEach { pending ->
                assertEquals(
                    OrgRequest.UNAVAILABLE,
                    orgRequestState(m, hasPendingRequest = pending, providerAvailable = false, readCompleted = true),
                    "membership=$m pending=$pending",
                )
            }
        }
        assertFalse(orgRequestEnabled(OrgRequest.UNAVAILABLE))
    }

    @Test
    fun `only AVAILABLE is clickable`() {
        // entries-driven so a new OrgRequest member has to be classified here rather than
        // silently inheriting "clickable" and offering an action that cannot work.
        assertEquals(
            setOf(OrgRequest.AVAILABLE),
            OrgRequest.entries.filter { orgRequestEnabled(it) }.toSet(),
        )
    }

    @Test
    fun `every outcome on both axes has a label and a description`() {
        // A missing branch here would render an empty button.
        OrgAccess.entries.forEach { access ->
            assertTrue(orgAccessLabel(access).isNotBlank(), "$access has no label")
            assertTrue(orgAccessDescription(access).isNotBlank(), "$access has no description")
        }
        OrgRequest.entries.forEach { state ->
            assertTrue(orgRequestLabel(state).isNotBlank(), "$state has no label")
            // Both variants: the member wording and the non-member wording.
            assertTrue(
                orgRequestDescription(state, Membership.NONE).isNotBlank(),
                "$state has no description for a non-member",
            )
            assertTrue(
                orgRequestDescription(state, Membership.ACTIVE).isNotBlank(),
                "$state has no description for a member",
            )
        }
    }

    @Test
    fun `labels name the action, and are distinct from each other`() {
        val labels = OrgAccess.entries.map { orgAccessLabel(it) } +
            OrgRequest.entries.map { orgRequestLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "two outcomes share a label: $labels")
        // Each says what will happen rather than naming the noun.
        assertTrue(orgRequestLabel(OrgRequest.AVAILABLE).startsWith("Request"))
        assertTrue(orgAccessLabel(OrgAccess.INSTALL_PLUGIN).startsWith("Install"))
        assertTrue(orgAccessLabel(OrgAccess.OPEN).startsWith("Open"))
    }

    @Test
    fun `the member and non-member wordings differ where they must`() {
        // "You are not a member of any organisation" is plainly false for the member who can now
        // also request one, and a sentence the reader can see is wrong costs more than the branch.
        assertNotEquals(
            orgRequestDescription(OrgRequest.AVAILABLE, Membership.NONE),
            orgRequestDescription(OrgRequest.AVAILABLE, Membership.ACTIVE),
        )
        assertFalse(
            orgRequestDescription(OrgRequest.AVAILABLE, Membership.ACTIVE).contains("not a member"),
        )
    }

    @Test
    fun `an offer with no member route means they really belong to nothing`() {
        // The wording depends on this. OrganisationSection asks orgRequestDescription for the
        // "You are not a member of any organisation" variant, and that sentence is only true when
        // no access route AND an available request together imply Membership.NONE. The two axes
        // are otherwise independent, so nothing else pins the one place they touch: a third
        // Membership value yielding no access route would tell a member they belong to nothing,
        // and only the prose would be wrong.
        for (m in listOf(null, Membership.NONE, Membership.ACTIVE)) {
            for (installed in listOf(false, true)) {
                for (pending in listOf(false, true)) {
                    for (completed in listOf(false, true)) {
                        val access = orgAccessRoute(m, installed)
                        val request = orgRequestState(
                            m,
                            pending,
                            providerAvailable = true,
                            readCompleted = completed,
                        )
                        if (access == null && request == OrgRequest.AVAILABLE) {
                            // Never ACTIVE: that is the case where "you are not a member of any
                            // organisation" would be shown to somebody who is one. NONE and null
                            // are both fine - null gets the neutral wording.
                            assertNotEquals(
                                Membership.ACTIVE,
                                m,
                                "the non-member wording would be shown to m=$m",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the Create tab is shown to everyone who can reach the organisation service`() {
        // The coverage the deleted organisationCtaNeedsCreateTab had, on the expression that
        // replaced it - which is the same expression whose previous version WAS the bug.
        // A non-publisher gets the tab, because the request form has nowhere else to live.
        assertTrue(createTabVisible(canPublish = false, organisationServiceAvailable = true))
        // A publisher gets it even on a host that cannot reach the service.
        assertTrue(createTabVisible(canPublish = true, organisationServiceAvailable = false))
        assertTrue(createTabVisible(canPublish = true, organisationServiceAvailable = true))
        // Neither: the tab would hold one disabled button reading "unavailable".
        assertFalse(createTabVisible(canPublish = false, organisationServiceAvailable = false))
    }

    @Test
    fun `tab visibility does not depend on membership`() {
        // The regression, stated as an invariant rather than a case. Nothing about who you belong
        // to may remove the tab: that coupling is what made the request form unreachable.
        for (m in listOf(null, Membership.NONE, Membership.ACTIVE)) {
            for (installed in listOf(false, true)) {
                for (pending in listOf(false, true)) {
                    for (completed in listOf(false, true)) {
                        // Membership feeds both axes; neither feeds the tab.
                        orgAccessRoute(m, installed)
                        orgRequestState(m, pending, providerAvailable = true, readCompleted = completed)
                        assertTrue(
                            createTabVisible(
                                canPublish = false,
                                organisationServiceAvailable = true,
                            ),
                            "m=$m would lose the tab",
                        )
                    }
                }
            }
        }
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
    fun `both decisions are total over their inputs`() {
        // Three states for membership, two for the plugin, two for pending, two for the provider:
        // no combination may throw, only ACTIVE may yield a member route, and the request state is
        // never absent - a section that renders nothing is the failure this change is about.
        val memberships = listOf(null, Membership.NONE, Membership.ACTIVE)
        for (m in memberships) {
            for (installed in listOf(false, true)) {
                val access = orgAccessRoute(m, installed)
                if (m == Membership.ACTIVE) {
                    assertNotNull(access, "m=$m installed=$installed")
                } else {
                    assertNull(access, "m=$m installed=$installed")
                }
            }
            for (pending in listOf(false, true)) {
                for (provider in listOf(false, true)) {
                    for (completed in listOf(false, true)) {
                        // assertNotNull here would be a tautology - the return type is not
                        // nullable. What is worth pinning across the whole cross-product is that
                        // no provider ALWAYS wins: it is the one state where the request cannot
                        // be submitted at all, so nothing may promote it to an offer.
                        val state = orgRequestState(m, pending, provider, completed)
                        val label = "m=$m pending=$pending provider=$provider done=$completed"
                        if (!provider) {
                            assertEquals(OrgRequest.UNAVAILABLE, state, label)
                        } else {
                            assertNotEquals(OrgRequest.UNAVAILABLE, state, label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Parsing the `get_my_organisations` body.
 *
 * The parse decides which member route the Toolbox offers and how the request is worded, and
 * every wrong answer still renders a plausible button, so the failure modes are quiet.
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
 * move the request control off "Request an organisation".
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
    fun `a reviewed request is a CONFIDENT no, not unknown`() {
        // This is what lets a rejection clear the flag. Reported as unknown, a rejected request
        // would keep "Request pending review" on screen with a disabled button until the app
        // restarted.
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
        // null, not false: the queue is not theirs, so this read says nothing about them.
        assertNull(parsePendingRequest(queue))

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
    fun `an empty own queue is a confident no, and anything unreadable is unknown`() {
        assertEquals(false, parsePendingRequest("""{"success":true,"data":[]}"""))
        // Inconclusive: we could not ask, so the previous value must survive.
        assertNull(parsePendingRequest("""{"success":false,"error":"Permission denied"}"""))
        assertNull(parsePendingRequest(null))
        assertNull(parsePendingRequest("not json"))
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

/**
 * The pending flag may only ever be turned on by a refresh, never off.
 */
class RetainPendingRequestTest {
    @Test
    fun `an inconclusive read does not clear a flag we already set`() {
        // A reviewer envelope, a refusal or a transport failure all read as null, and a BOSS
        // admin who submits would otherwise watch the button revert one round trip later.
        assertEquals(true, retainPendingRequest(previouslyKnown = true, fromServer = null))
    }

    @Test
    fun `a CONFIDENT no clears it`() {
        // The rejection path. Monotonic retention kept "Request pending review" on screen with a
        // disabled button until the app restarted - the same lockout the reviewer branch exists
        // to prevent, through a different door.
        assertEquals(false, retainPendingRequest(previouslyKnown = true, fromServer = false))
    }

    @Test
    fun `a server true sets it`() {
        assertEquals(true, retainPendingRequest(previouslyKnown = false, fromServer = true))
    }

    @Test
    fun `both false stays false`() {
        assertEquals(false, retainPendingRequest(previouslyKnown = false, fromServer = false))
        assertEquals(false, retainPendingRequest(previouslyKnown = false, fromServer = null))
    }

    @Test
    fun `it survives repeated INCONCLUSIVE reads but yields to a confident one`() {
        var flag = false
        flag = retainPendingRequest(flag, fromServer = true)
        repeat(5) { flag = retainPendingRequest(flag, fromServer = null) }
        assertEquals(true, flag, "an unreadable queue must not clear it")

        flag = retainPendingRequest(flag, fromServer = false)
        assertEquals(false, flag, "a rejection must")
    }

    // ---- the two optional fields on the request form ------------------------------------

    @Test
    fun `a website must carry an http scheme`() {
        // Not tidiness. This value is rendered as a LINK on the organisation's web
        // pages and is filled in by any authenticated user submitting a request, so a
        // javascript: or data: URL here would be stored XSS with a self-service entry
        // point. The database refuses it too; this is the copy that explains why.
        assertNotNull(organisationWebsiteError("javascript:alert(1)"))
        assertNotNull(organisationWebsiteError("data:text/html,<script>"))
        assertNotNull(organisationWebsiteError("acme.com"))
        assertNull(organisationWebsiteError("https://acme.com"))
        assertNull(organisationWebsiteError("http://acme.com/path?q=1"))
    }

    @Test
    fun `an empty website is fine, because the field is optional`() {
        assertNull(organisationWebsiteError(""))
        assertNull(organisationWebsiteError("   "))
    }

    @Test
    fun `a website that is too long or has spaces is refused`() {
        assertNotNull(organisationWebsiteError("https://" + "a".repeat(500) + ".com"))
        assertNotNull(organisationWebsiteError("https://acme.com/a path"))
    }

    @Test
    fun `the domain names the common mistake rather than refusing generically`() {
        // People paste the website into the domain field. "That does not look like a
        // domain" is true and useless; saying which part to remove is the difference
        // between a fixable message and a guess.
        assertEquals(
            "Just the domain, without http:// - for example acme.com",
            organisationDomainError("https://acme.com"),
        )
        assertEquals(
            "Just the domain, without the @ - for example acme.com",
            organisationDomainError("someone@acme.com"),
        )
        assertNotNull(organisationDomainError("acme.com/team"))
    }

    @Test
    fun `a plain domain is accepted and an empty one is optional`() {
        assertNull(organisationDomainError("acme.com"))
        assertNull(organisationDomainError("mail.acme.co.uk"))
        assertNull(organisationDomainError(""))
        assertNotNull(organisationDomainError("not a domain"))
    }
}
