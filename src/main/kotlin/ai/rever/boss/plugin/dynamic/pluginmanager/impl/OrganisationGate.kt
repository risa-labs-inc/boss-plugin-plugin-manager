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
 * [hasOrganisation] is null while the membership lookup is in flight, or when
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
): OrganisationCta? =
    when (membership) {
        null -> null
        Membership.NONE -> OrganisationCta.CREATE
        Membership.PENDING -> OrganisationCta.REQUEST_PENDING
        Membership.ACTIVE ->
            if (pluginInstalled) OrganisationCta.OPEN else OrganisationCta.INSTALL_PLUGIN
    }

/**
 * What the server says about this user's organisations.
 *
 * Three states, not a Boolean, because a pending request is neither membership nor its absence:
 * treating it as absence hides the fact that a request was submitted at all.
 */
enum class Membership {
    /** An active membership in at least one organisation. */
    ACTIVE,

    /** No active membership, but at least one request awaiting review. */
    PENDING,

    /** Nothing at all. */
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
            "Your request has been submitted and is waiting for a BOSS administrator to review " +
                "it. Nothing more to do here."

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
 * Returns null for anything that is not a confident yes or no - a transport
 * failure, a refusal envelope, malformed JSON. Null hides the call to action,
 * which is the right failure: offering "Request an organisation" because a read
 * failed pushes somebody toward creating a duplicate of one they are already in.
 *
 * A pending row is reported as PENDING, not as membership and not as nothing: it is what lets
 * the call to action say "Request pending review" instead of silently offering the same button
 * again after a submission.
 */
fun parseMembership(raw: String?): Membership? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val rows = root["data"] as? JsonArray ?: return null

        val statuses =
            rows.map { element ->
                (element as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
            }

        when {
            // Absent status counts as active: get_my_organisations projects it today, but a
            // future shape that omits it for the common case must not read as "not a member".
            statuses.any { it == null || it == "active" } -> Membership.ACTIVE
            statuses.isNotEmpty() -> Membership.PENDING
            else -> Membership.NONE
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
