package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which organisation call to action the Toolbox should offer, if any.
 *
 * Pure, and separated from the view for that reason: the decision has four
 * outcomes over two unknowns, and two of those unknowns can each be "not yet
 * known" rather than a boolean. Getting that wrong is not a rendering bug, it
 * is a flash of "Create Organisation" shown to somebody who already has three
 * of them, every time the panel opens.
 */
enum class OrganisationCta {
    /** No organisation yet: offer to request one. */
    CREATE,

    /**
     * A request is in the queue.
     *
     * Rendered rather than collapsed into CREATE because without it, submitting a request changes
     * nothing on screen at all - the same button with the same text - and the natural response is
     * to submit again, which returns "already in use" and reads as a failure rather than a
     * duplicate.
     */
    REQUEST_PENDING,

    /** Has one, but the Organisation plugin is not installed: offer to install it. */
    INSTALL_PLUGIN,

    /** Has one and the plugin is installed: offer to open the panel. */
    OPEN,
}

/**
 * Decide the call to action.
 *
 * [membership] is null while the membership lookup is in flight, or when
 * there is no Supabase provider to ask. **Null renders nothing.** That is the
 * whole reason this is a nullable Boolean rather than a Boolean defaulting to
 * false: "we have not asked yet" and "you belong to none" are different states
 * that would otherwise both show CREATE, and the wrong one is shown to every
 * existing member for the duration of a round trip.
 *
 * [pluginInstalled] has no such ambiguity - the installed list is local and
 * known synchronously.
 */
fun organisationCta(
    membership: Membership?,
    pluginInstalled: Boolean,
    hasPendingRequest: Boolean = false,
): OrganisationCta? =
    when (membership) {
        null -> null
        Membership.ACTIVE ->
            if (pluginInstalled) OrganisationCta.OPEN else OrganisationCta.INSTALL_PLUGIN
        // Membership wins over a pending request: someone who has since been added to an
        // organisation should be pointed at it, not at a request that is now moot.
        Membership.NONE ->
            if (hasPendingRequest) OrganisationCta.REQUEST_PENDING else OrganisationCta.CREATE
    }

/**
 * What the server says about this user's organisations.
 *
 * Three states, not a Boolean, because a pending request is neither membership nor its absence:
 * treating it as absence hides the fact that a request was submitted at all.
 */
enum class Membership {
    /** An active membership in at least one NON-system organisation. */
    ACTIVE,

    /** None. The seeded boss organisation does not count - see [parseMembership]. */
    NONE,
}

/** True when this call to action should be clickable. A pending request has nothing to do. */
fun organisationCtaEnabled(cta: OrganisationCta): Boolean = cta != OrganisationCta.REQUEST_PENDING

/**
 * Button label for a call to action.
 *
 * Kept beside the decision rather than in the view so the two cannot drift, and
 * so a test can assert the pairing. Each label names what will happen, not what
 * the thing is: a control that says "Organisation" leaves the reader guessing.
 */
fun organisationCtaLabel(cta: OrganisationCta): String =
    when (cta) {
        OrganisationCta.CREATE -> "Request an organisation"
        OrganisationCta.REQUEST_PENDING -> "Request pending review"
        OrganisationCta.INSTALL_PLUGIN -> "Install the Organisation plugin"
        OrganisationCta.OPEN -> "Open Organisation"
    }

/** Explanatory line under the heading. */
fun organisationCtaDescription(cta: OrganisationCta): String =
    when (cta) {
        OrganisationCta.CREATE ->
            "You are not a member of any organisation. Requesting one opens a form; a BOSS " +
                "administrator reviews it before the organisation is created."

        OrganisationCta.REQUEST_PENDING ->
            // Accurate now that this state comes from organisation_requests: those really are
            // reviewed by a BOSS administrator holding organisation.approve. It was wrong while
            // the state came from a `pending` MEMBERSHIP, which is a request to join an existing
            // organisation and is approved by that organisation's own admin.
            "Your request to create an organisation is waiting for a BOSS administrator to " +
                "review it. Nothing more to do here."

        OrganisationCta.INSTALL_PLUGIN ->
            "You belong to an organisation, but the Organisation plugin is not installed. " +
                "Install it to manage members, roles and plugin visibility."

        OrganisationCta.OPEN ->
            "Manage your organisation's members, roles, invite links and plugin visibility."
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
 * envelope, malformed JSON. Null hides the call to action, which is the right failure: offering
 * "Request an organisation" because a read failed pushes somebody toward duplicating one they are
 * already in.
 *
 * SYSTEM ORGANISATIONS ARE IGNORED, and this is the whole correctness of the CREATE branch.
 * The seed makes every user an active member of the `boss` organisation and `handle_new_user`
 * keeps every future signup there, so `get_my_organisations` returns at least one active row for
 * literally everybody. Counting it made ACTIVE the only reachable answer and CREATE dead code in
 * production - the unit tests passed only because they fed `Membership.NONE` directly, which no
 * real response can produce.
 *
 * Only an ACTIVE membership of a NON-system organisation counts. A `pending` or `invited` row is
 * about joining an existing organisation and is deliberately not membership here - see
 * [parsePendingRequest] for the state that actually drives REQUEST_PENDING.
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
 * submission therefore could never move the button off CREATE - the exact failure
 * REQUEST_PENDING was added to prevent.
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
