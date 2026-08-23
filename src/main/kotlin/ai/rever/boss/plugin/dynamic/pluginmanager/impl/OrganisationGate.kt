package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The MEMBER route into the Organisation plugin, if this user has one.
 *
 * One of the TWO axes this file decides. Requesting an organisation is the other
 * ([orgRequestState]), and they are independent: a member may request a second organisation, and
 * somebody with a request in the queue still belongs to whatever they already belong to.
 *
 * They used to be one four-outcome enum, and collapsing them was the bug. `ACTIVE` landed on
 * OPEN / INSTALL_PLUGIN, which meant the request form did not exist for a member - and because
 * the Create tab was revealed only for the two request branches, a member got no tab at all.
 * Every user is seeded into `@risa`-sized organisations, so that was most of them.
 */
enum class OrgAccess {
    /** Belongs to one, but the Organisation plugin is not installed: offer to install it. */
    INSTALL_PLUGIN,

    /** Belongs to one and the plugin is installed: offer to open the panel. */
    OPEN,
}

/**
 * Decide the member route, or null when there is none to offer.
 *
 * Null for two different reasons, and neither is an error: they belong to no organisation, or the
 * lookup has not answered yet. Both render no access button - the difference between them matters
 * only to [orgRequestState], which is where it is expressed.
 *
 * [pluginInstalled] has no such ambiguity - the installed list is local and known synchronously.
 */
fun orgAccessRoute(
    membership: Membership?,
    pluginInstalled: Boolean,
): OrgAccess? =
    when (membership) {
        Membership.ACTIVE -> if (pluginInstalled) OrgAccess.OPEN else OrgAccess.INSTALL_PLUGIN
        Membership.NONE, null -> null
    }

/**
 * Whether requesting an organisation is on offer, and why not when it is not.
 *
 * The other axis. Four states rather than a Boolean because three different things all render as
 * "no button" and the user is owed a different sentence for each: we have not asked yet, you
 * already have one in the queue, and this host cannot ask at all.
 *
 * NOT gated on membership. That gate is what made this unreachable for anybody who already
 * belonged to something, and there is nothing wrong with a member wanting a second organisation -
 * a BOSS administrator reviews every request either way.
 */
enum class OrgRequest {
    /** Offer the form. */
    AVAILABLE,

    /**
     * One of the caller's own requests is already awaiting review.
     *
     * Rendered rather than collapsed into AVAILABLE because without it, submitting a request
     * changes nothing on screen at all - the same button with the same text - and the natural
     * response is to submit again, which returns "already in use" and reads as a failure rather
     * than a duplicate.
     */
    PENDING,

    /**
     * The membership lookup has not answered yet.
     *
     * Shown as a disabled, neutral line rather than nothing. Rendering nothing was the old
     * behaviour and it is what made the whole tab blank for a non-publisher whenever the read was
     * slow or failed. The original concern - never flash the WRONG label at somebody who already
     * has three organisations - is preserved by the label being neutral, not by the section being
     * absent.
     */
    UNKNOWN,

    /**
     * No `supabaseDataProvider`, so there is nothing to ask and nothing to submit to.
     *
     * Distinct from UNKNOWN on purpose. They were indistinguishable before, and the difference is
     * whether waiting helps: UNKNOWN resolves on the next refresh, UNAVAILABLE never does. Showing
     * "Checking..." forever is worse than saying so.
     */
    UNAVAILABLE,
}

/**
 * Decide whether the request form is on offer.
 *
 * [providerAvailable] is the host's `supabaseDataProvider`, not a network check: false means the
 * plugin has no way to reach the RPC at all, which is a permanent condition for that host.
 *
 * A pending request wins over everything except an absent provider - it is the one state where
 * acting again makes things worse.
 */
fun orgRequestState(
    membership: Membership?,
    hasPendingRequest: Boolean,
    providerAvailable: Boolean,
    readCompleted: Boolean,
): OrgRequest =
    when {
        !providerAvailable -> OrgRequest.UNAVAILABLE
        hasPendingRequest -> OrgRequest.PENDING
        // Deliberately AFTER the pending check: a submitted request is known locally the moment it
        // succeeds, and it should not revert to "Request an organisation" while the membership
        // re-read is in flight.
        //
        // UNKNOWN only while a read is genuinely outstanding. A read that CAME BACK and told us
        // nothing - transport failure, refusal envelope, malformed body - offers the request
        // anyway. Being conservative there was right while requesting was gated on membership,
        // because guessing wrong denied it; now that the two are decoupled a failed read affects
        // only the WORDING, and orgRequestDescription has a membership-neutral variant for
        // exactly this. Refusing to offer it instead leaves a permanent "Checking your
        // organisations..." with no way forward, which is the failure this whole change is about.
        // The server validates the submission regardless.
        membership == null && !readCompleted -> OrgRequest.UNKNOWN
        else -> OrgRequest.AVAILABLE
    }

/**
 * What the server says about this user's organisations.
 *
 * Two states plus null at the call sites, because "we have not asked yet" and "you belong to none"
 * drive different sentences - see [OrgRequest.UNKNOWN].
 */
enum class Membership {
    /** An active membership in at least one NON-system organisation. */
    ACTIVE,

    /** None. The seeded boss organisation does not count - see [parseMembership]. */
    NONE,
}

/**
 * Should the Create tab be shown?
 *
 * A named function rather than an expression in the composable, because the PREVIOUS version of
 * this expression was the bug and an inline one cannot be tested. It is deliberately NOT a
 * function of membership - that gate is what made the request form unreachable for a member - and
 * it is still a function of the service existing, because a tab whose only content is
 * "Requesting is unavailable here" is an apology rather than a feature.
 */
fun createTabVisible(canPublish: Boolean, organisationServiceAvailable: Boolean): Boolean =
    canPublish || organisationServiceAvailable

/** True when the request control should be clickable. Only AVAILABLE has anything to do. */
fun orgRequestEnabled(state: OrgRequest): Boolean = state == OrgRequest.AVAILABLE

/**
 * Button label for the member route.
 *
 * Kept beside the decision rather than in the view so the two cannot drift, and so a test can
 * assert the pairing. Each label names what will happen, not what the thing is: a control that
 * says "Organisation" leaves the reader guessing.
 */
fun orgAccessLabel(access: OrgAccess): String =
    when (access) {
        OrgAccess.INSTALL_PLUGIN -> "Install the Organisation plugin"
        OrgAccess.OPEN -> "Open Organisation"
    }

/** Label for the request control, in every state including the ones that cannot be clicked. */
fun orgRequestLabel(state: OrgRequest): String =
    when (state) {
        OrgRequest.AVAILABLE -> "Request an organisation"
        OrgRequest.PENDING -> "Request pending review"
        OrgRequest.UNKNOWN -> "Checking your organisations..."
        OrgRequest.UNAVAILABLE -> "Requesting is unavailable here"
    }

/** Explanatory line for the member route. */
fun orgAccessDescription(access: OrgAccess): String =
    when (access) {
        OrgAccess.INSTALL_PLUGIN ->
            "You belong to an organisation, but the Organisation plugin is not installed. " +
                "Install it to manage members, roles and plugin visibility."

        OrgAccess.OPEN ->
            "Manage your organisation's members, roles, invite links and plugin visibility."
    }

/**
 * Explanatory line for the request control.
 *
 * Takes [membership] rather than a derived Boolean, and three AVAILABLE variants rather than two.
 * "You are not a member of any organisation" is plainly false for the member who can now also
 * request one, and it is UNVERIFIED when the membership read failed - which is a state AVAILABLE
 * is now reachable from. A sentence the reader can see is wrong costs more than the branch does.
 */
fun orgRequestDescription(state: OrgRequest, membership: Membership?): String =
    when (state) {
        OrgRequest.AVAILABLE ->
            when (membership) {
                Membership.ACTIVE ->
                    "Need another organisation? Requesting one opens a form; a BOSS " +
                        "administrator reviews it before the organisation is created."

                Membership.NONE ->
                    "You are not a member of any organisation. Requesting one opens a form; a " +
                        "BOSS administrator reviews it before the organisation is created."

                // Says nothing about what they belong to, because we could not find out.
                null ->
                    "Requesting an organisation opens a form; a BOSS administrator reviews it " +
                        "before the organisation is created."
            }

        OrgRequest.PENDING ->
            // Accurate because this state comes from organisation_requests: those really are
            // reviewed by a BOSS administrator holding organisation.approve. It was wrong while
            // the state came from a `pending` MEMBERSHIP, which is a request to join an existing
            // organisation and is approved by that organisation's own admin.
            "Your request to create an organisation is waiting for a BOSS administrator to " +
                "review it. Nothing more to do here."

        OrgRequest.UNKNOWN ->
            "Looking up the organisations you belong to."

        OrgRequest.UNAVAILABLE ->
            "This BOSS build cannot reach the organisation service, so a request cannot be " +
                "submitted from here."
    }

/** The store id of the Organisation plugin, and its panel id. */
object OrganisationPlugin {
    const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.organisation"
    const val PANEL_ID = "organisation"
}

/**
 * Read this user's membership state out of a `get_my_organisations` response body.
 *
 * Returns null for anything that is not a confident answer - a transport failure, a refusal
 * envelope, malformed JSON. Null means UNKNOWN, never "belongs to none": the caller keeps the
 * last known value rather than writing this over it, and what null changes is only the WORDING,
 * never whether the request is offered. This paragraph used to argue the opposite - that a failed
 * read must hide the offer - which was right while requesting was gated on membership and became
 * wrong the moment the two were decoupled. See [orgRequestState].
 *
 * SYSTEM ORGANISATIONS ARE IGNORED, and this is the whole correctness of the not-a-member answer.
 * The seed makes every user an active member of the `boss` organisation and `handle_new_user`
 * keeps every future signup there, so `get_my_organisations` returns at least one active row for
 * literally everybody. Counting it made ACTIVE the only reachable answer and `Membership.NONE`
 * dead in production - the unit tests passed only because they fed it directly, which no real
 * response can produce.
 *
 * Only an ACTIVE membership of a NON-system organisation counts. A `pending` or `invited` row is
 * about joining an existing organisation and is deliberately not membership here - see
 * [parsePendingRequest] for the state that actually drives [OrgRequest.PENDING].
 */
fun parseMembership(raw: String?): Membership? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val rows = root["data"] as? JsonArray ?: return null

        val realActive =
            rows.any { element ->
                val row = element as? JsonObject ?: return@any false
                val isSystem = row["is_system"]?.jsonPrimitive?.booleanOrNull ?: false
                val status = row["status"]?.jsonPrimitive?.contentOrNull
                // Absent status counts as active: get_my_organisations projects it today, but a
                // future shape that omits it for the common case must not read as "not a member".
                !isSystem && (status == null || status == "active")
            }

        if (realActive) Membership.ACTIVE else Membership.NONE
    }.getOrNull()
}

/**
 * Does the CALLER have an organisation-creation request awaiting review?
 *
 * A SEPARATE read, from `list_organisation_requests`, and it has to be:
 * `submit_organisation_request` writes to `organisation_requests` and creates no membership row
 * at all, while `get_my_organisations` reads `organisation_members`. Refreshing membership after a
 * submission therefore could never move the button off "Request an organisation" - the exact
 * failure [OrgRequest.PENDING] was added to prevent.
 *
 * REVIEWERS GET `null`, WHATEVER THE QUEUE HOLDS. The RPC scopes to the caller's own requests
 * only for a NON-reviewer; for a BOSS admin holding `organisation.approve` it returns the whole
 * queue, and the envelope's `is_reviewer` flag is how we know which we got. Without this a BOSS
 * admin who belongs to no organisation - reachable, since that is the branch we are in - would
 * see "Request pending review" against somebody else's request, with the button disabled, and
 * could not request an organisation at all until the queue drained.
 *
 * The plugin cannot filter by owner instead: PluginContext exposes no current-user id, so there
 * is nothing to match `requester_id` against. The cost is that a reviewer does not see their own
 * pending state, which is a missing hint rather than a lockout - the safe direction.
 */
fun parsePendingRequest(raw: String?): Boolean? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        // A reviewer's queue is not theirs, so this read says nothing about the caller.
        if (root["is_reviewer"]?.jsonPrimitive?.booleanOrNull == true) return null
        val rows = root["data"] as? JsonArray ?: return null
        rows.any { element ->
            (element as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "pending"
        }
    }.getOrNull()
}


/**
 * Slug validation, mirroring the database CHECK exactly.
 *
 * Checked here as well as server-side so a typo is a message under the field
 * rather than a round trip that comes back with a constraint error. The pattern
 * is `^[a-z][a-z0-9_]{1,30}$`.
 *
 * UNDERSCORES, NEVER HYPHENS. Organisation role names derive from the slug
 * (`<slug>_admin`, `<slug>_user`) and `roles.name` is validated
 * `^[a-z][a-z0-9_]{2,50}$`, so a hyphen would make the mapping partial - the
 * organisation would be created and its roles would not.
 *
 * Returns null when valid, or the reason it is not.
 */
fun organisationSlugError(slug: String): String? {
    val value = slug.trim()
    return when {
        value.isEmpty() -> "Enter a short identifier."
        value.length < 2 -> "Too short - at least 2 characters."
        value.length > 31 -> "Too long - at most 31 characters."
        value.contains('-') -> "Use underscores, not hyphens. Role names derive from this."
        !value[0].isLetter() -> "Start with a letter."
        !Regex("^[a-z][a-z0-9_]{1,30}$").matches(value) ->
            "Use lowercase letters, digits and underscores only."
        else -> null
    }
}

/** Validation for the organisation name. */
fun organisationNameError(name: String): String? {
    val value = name.trim()
    return when {
        value.isEmpty() -> "Enter a name."
        value.length > 120 -> "Too long - at most 120 characters."
        else -> null
    }
}

/**
 * Read a `submit_organisation_request` response.
 *
 * Returns null on success, or the message to show. The server's own wording is
 * preferred where it gives one: "Slug \"boss\" is reserved or already in use"
 * is more useful than anything this side could invent, and the reserved-slug and
 * collision rules live there.
 */
fun submitRequestError(raw: String?): String? {
    if (raw.isNullOrBlank()) return "Could not reach the server. Please try again."
    val parsed =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return "Could not reach the server. Please try again."
    if (parsed["success"]?.jsonPrimitive?.booleanOrNull == true) return null
    return parsed["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: "The request was refused."
}

/**
 * Combine a freshly-read pending-request flag with what we already believed.
 *
 * A refresh retains the old value only when the read was INCONCLUSIVE (null). A confident
 * `false` clears it.
 *
 * The first version of this was monotonic - `fromServer || previouslyKnown` - which fixed the
 * two inconclusive cases below and introduced a worse one: an admin REJECTING the request is a
 * confident false, so the user kept "Request pending review" with a disabled button for the life
 * of the panel, and canUnload:false makes that until the app restarts. The same lockout the
 * reviewer branch was written to prevent, through a different door.
 *
 * Inconclusive, and therefore retained:
 *
 *  - [parsePendingRequest] returns false for a REVIEWER, because the queue it sees is not
 *    theirs. A BOSS admin who submits a request would otherwise watch the button revert to
 *    "Request an organisation" one round trip later - the resubmit path the pending state exists
 *    to close.
 *  - A transport failure or a refusal also yields false, which would discard a known-true value
 *    on a failed read.
 *
 * That is the "unknown is not no" rule the rest of this file is built on - the bug was
 * collapsing *unknown* and *confident no* into one value.
 */
fun retainPendingRequest(
    previouslyKnown: Boolean,
    fromServer: Boolean?,
): Boolean = fromServer ?: previouslyKnown

/**
 * Validation for the optional website.
 *
 * Mirrors the server's rule rather than inventing a looser one: http and https
 * only, because this value is rendered as a LINK on the organisation's web pages,
 * and a `javascript:` or `data:` URL there would be script execution from a field
 * any authenticated user can fill in. The database refuses it too - this is only
 * so a typo is a message under the field instead of a round trip.
 *
 * Returns null when valid or empty, since the field is optional.
 */
fun organisationWebsiteError(website: String): String? {
    val value = website.trim()
    if (value.isEmpty()) return null
    return when {
        !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(value) ->
            "Start with http:// or https://"
        value.length > 500 -> "Too long - at most 500 characters."
        value.contains(' ') -> "A web address cannot contain spaces."
        else -> null
    }
}

/**
 * Validation for the optional email domain.
 *
 * The domain is NOT cosmetic: once verified by a DNS TXT record it lets anyone
 * with a matching address find and join the organisation, so it is worth getting
 * right at request time. A scheme here is the common mistake - people paste the
 * website - and it is worth naming rather than refusing generically.
 */
fun organisationDomainError(domain: String): String? {
    val value = domain.trim().lowercase()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("http://") || value.startsWith("https://") ->
            "Just the domain, without http:// - for example acme.com"
        value.contains('@') -> "Just the domain, without the @ - for example acme.com"
        value.contains('/') -> "Just the domain, with no path."
        !Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$").matches(value) ->
            "That does not look like a domain."
        else -> null
    }
}

/**
 * The system organisation's slug.
 *
 * The platform's own store, and the organisation every user is a member of. Named once here rather
 * than typed at each call site: the publish-target default and the server-side derivation both key
 * on it, and a literal in two places is a literal that eventually disagrees.
 */
const val SYSTEM_ORG_SLUG = "boss"

/**
 * An organisation the signed-in user may publish a plugin for.
 *
 * [orgId] is what the publish request carries; [slug] and [name] are what the picker shows. All
 * three come from one `get_my_organisations` row, so a target cannot exist with an id the server
 * would not recognise.
 */
data class PublishTarget(
    val orgId: String,
    val slug: String,
    val name: String,
    /**
     * The system organisation, `@boss`.
     *
     * Load-bearing rather than informational: publishing for an organisation is now a right an
     * organisation's own publish policy can grant, with no global permission involved, and the one
     * place that right must never reach is the platform's own store. Every signup is a member of
     * `@boss`, so if its policy ever admitted members this flag is what keeps the BOSS store off
     * the picker. The server refuses it too - this only avoids offering a choice that would 403.
     */
    val isSystem: Boolean = false,
)

/**
 * The organisations this user may publish for, from a `get_my_organisations` response.
 *
 * FILTERED ON THE SERVER'S OWN ANSWER, never re-derived here. `can_publish` is computed by
 * `user_can_publish_org_plugin` - the same function the publish endpoint gates on - so a target in
 * this list is one the server will accept, and one it rejects never appears. Reimplementing the
 * policy client-side (owner_only, admins, members, publish_role_id) would be a second copy that
 * drifts, and every drift is either a 403 on a plugin somebody was told they could publish or an
 * option missing for one they can.
 *
 * Returns empty for anything that is not a confident answer: a refusal, malformed JSON, or no
 * provider. Empty means the picker is not offered and the server derives the organisation as
 * before - which is the safe direction, since it cannot attribute a plugin somewhere the user
 * did not choose.
 *
 * `is_system` organisations are NOT excluded here, unlike the publish path's own derivation. There
 * the point was that `boss` holds everyone so it could not disambiguate; here the user is picking
 * explicitly, and "publish this to the BOSS store itself" is a legitimate thing to choose when the
 * server says they may. They are FLAGGED instead ([PublishTarget.isSystem]), because that is only
 * legitimate for somebody holding `plugins.create`: an org-scoped publisher may publish for their
 * own organisations and nothing else, and the caller decides which list to offer.
 */
fun parsePublishTargets(raw: String?): List<PublishTarget> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyList()
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        val rows = root["data"] as? JsonArray ?: return emptyList()

        rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            if (row["can_publish"]?.jsonPrimitive?.booleanOrNull != true) return@mapNotNull null

            // An active membership only. A pending or invited row can carry can_publish = true for
            // a global admin, and offering an organisation somebody has not joined as a publish
            // target reads as membership they do not have.
            val status = row["status"]?.jsonPrimitive?.contentOrNull
            if (status != null && status != "active") return@mapNotNull null

            val id = row["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val slug = row["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PublishTarget(
                orgId = id,
                slug = slug,
                isSystem = row["is_system"]?.jsonPrimitive?.booleanOrNull ?: false,
                // Falls back to the slug rather than dropping the row: a nameless organisation is
                // still one you may publish for.
                name = row["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: slug,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Which organisation the publish form should start on, or null to leave it unset.
 *
 * BOSS first, but only for somebody who may actually publish there: it is the platform's own store
 * and where every plugin published before the picker existed was attributed, so it is the right
 * default for a `plugins.create` holder and the one thing an org-scoped publisher must never be
 * pointed at. Then the sole target, which covers the common case of one organisation and nothing to
 * choose. Otherwise null: several candidates and no default is a choice for the person publishing,
 * and for a global publisher the server derives it exactly as it did before this control existed.
 *
 * Keyed on [PublishTarget.isSystem] rather than the slug. The flag is the server's own answer;
 * matching `"boss"` would make an organisation that happens to be called that the default, and
 * would silently stop working the day the system organisation is renamed.
 */
fun defaultPublishTarget(targets: List<PublishTarget>, canPublishGlobally: Boolean): String? =
    targets.firstOrNull { canPublishGlobally && it.isSystem }?.orgId
        ?: targets.singleOrNull()?.orgId

/**
 * The token from `mint_organisation_handoff_token`, or null.
 *
 * Null is the ordinary answer, not an error: minting is members-only, so anyone outside the owning
 * organisation gets a refusal here and opens the plugin's page signed out - which is the public
 * view they are entitled to. The caller must not report anything for it.
 *
 * The envelope is checked before the token is read. `{"success": false, "error": "..."}` carries no
 * token, and reading a missing key as an empty string would put `?t=` on the URL with nothing after
 * it, which the page would try to exchange and refuse.
 */
fun parseHandoffToken(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        root["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
