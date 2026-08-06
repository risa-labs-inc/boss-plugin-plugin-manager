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
    hasOrganisation: Boolean?,
    pluginInstalled: Boolean,
): OrganisationCta? =
    when (hasOrganisation) {
        null -> null
        false -> OrganisationCta.CREATE
        true -> if (pluginInstalled) OrganisationCta.OPEN else OrganisationCta.INSTALL_PLUGIN
    }

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
        OrganisationCta.INSTALL_PLUGIN -> "Install the Organisation plugin"
        OrganisationCta.OPEN -> "Open Organisation"
    }

/** Explanatory line under the heading. */
fun organisationCtaDescription(cta: OrganisationCta): String =
    when (cta) {
        OrganisationCta.CREATE ->
            "You are not a member of any organisation. Requesting one opens a form; a BOSS " +
                "administrator reviews it before the organisation is created."

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
 * Read "does this user belong to any organisation" out of a
 * `get_my_organisations` response body.
 *
 * Returns null for anything that is not a confident yes or no - a transport
 * failure, a refusal envelope, malformed JSON. Null hides the call to action,
 * which is the right failure: offering "Request an organisation" because a read
 * failed pushes somebody toward creating a duplicate of one they are already in.
 *
 * A row is only counted when its status is active or absent. The RPC returns
 * pending memberships too, and a pending request does not make you a member -
 * counting it would hide the create offer from someone still waiting, with no
 * explanation of why.
 */
fun parseHasOrganisation(raw: String?): Boolean? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root["success"]?.jsonPrimitive?.booleanOrNull != true) return null
        val rows = root["data"] as? JsonArray ?: return null
        rows.any { element ->
            val status =
                (element as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull
            status == null || status == "active"
        }
    }.getOrNull()
}
