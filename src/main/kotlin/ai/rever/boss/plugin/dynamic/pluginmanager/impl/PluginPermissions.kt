package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Permission required to create a plugin, or to publish a version of one you
 * already own. Held by the `boss_plugin_admin` role and inherited by
 * `boss_admin` and `admin`.
 *
 * Deliberately not `plugins.admin.publish`: that permission's RLS policy has no
 * author scoping, so it authorizes updates to *any* plugin, and it stays
 * reserved for store-wide moderation (verify / delete / enable / disable).
 */
internal const val PLUGIN_CREATE_PERMISSION = "plugins.create"

private val permissionJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The effective permissions carried by an access token's `user_permissions`
 * claim — the set injected by Supabase's `custom_access_token_hook`, covering
 * the user's own roles plus everything inherited through the role hierarchy.
 *
 * Decodes the JWT payload locally and does **not** verify the signature: this
 * drives UI affordances only, and every gated action is re-checked server-side.
 * Returns an empty set for a missing, malformed, or claim-less token, so an
 * unreadable token denies rather than admits.
 *
 * Extracted from the API impl so it can be unit-tested without constructing one
 * (that constructor builds a Supabase client), and so a multi-permission check
 * decodes the token once instead of once per permission.
 */
internal fun tokenPermissions(token: String?): Set<String> {
    if (token.isNullOrBlank()) return emptySet()
    return try {
        val parts = token.split(".")
        if (parts.size != 3) return emptySet()
        // getUrlDecoder handles the base64url alphabet, and its contract accepts
        // the missing padding JWTs omit ("interpreted as the end of the encoded
        // byte data, but is not required"). A length % 4 == 1 payload throws,
        // which the catch below turns into "no permissions".
        val decoded = String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        val perms = permissionJson.parseToJsonElement(decoded).jsonObject["user_permissions"]?.jsonArray
            ?: return emptySet()
        perms.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

/**
 * Whether a user holds the GLOBAL publishing right: store admin, or a token carrying
 * [PLUGIN_CREATE_PERMISSION].
 *
 * This is the unrestricted one - it reaches the BOSS store itself. It is no longer the only way to
 * publish (see [canPublishAnywhereWith]), which is why it is named for what it is rather than for
 * the tab it used to gate.
 *
 * Pure so the short-circuit itself is testable — inverting `isAdmin ||` is the
 * kind of edit a refactor makes silently, and the API impl cannot be constructed
 * in a test.
 */
internal fun canPublishGloballyWith(isAdmin: Boolean, token: String?): Boolean =
    isAdmin || PLUGIN_CREATE_PERMISSION in tokenPermissions(token)

/**
 * The organisations a publish may actually be attributed to by an org-scoped publisher: everything
 * the server said they may publish for, minus the system organisation.
 *
 * `@boss` is dropped even though `get_my_organisations` reports `can_publish` for it, because that
 * column answers the organisation's own publish policy and says nothing about the store the
 * organisation IS. Offering it would present a choice the server refuses.
 */
internal fun orgPublishTargets(targets: List<PublishTarget>): List<PublishTarget> =
    targets.filter { !it.isSystem }

/**
 * Whether the Create tab's publishing surfaces are reachable at all.
 *
 * Two ways, mirroring the server's own gate in `services/publish-authz.ts`: the global right, or at
 * least one organisation the server says this user may publish for. The second is why an approved
 * organisation can ship a plugin without a platform admin granting anything - its admin role
 * satisfies the default `publish_policy` of 'admins' on the day it is created.
 *
 * Takes the resolved right rather than the token so the two inputs cannot be confused at a call
 * site, and so this stays a pure predicate over both.
 */
internal fun canPublishAnywhereWith(
    globalRight: Boolean,
    targets: List<PublishTarget>,
): Boolean = globalRight || orgPublishTargets(targets).isNotEmpty()

/**
 * Whether a user may install a plugin declaring [requiredPermissions]. Empty ⇒
 * open to all (the `user.read` baseline every legacy plugin relies on); admins
 * bypass; otherwise every required permission must be held. Mirrors the
 * server-side /download gate so the UI never offers an install that would 403.
 */
internal fun canInstallWith(
    isAdmin: Boolean,
    token: String?,
    requiredPermissions: List<String>,
): Boolean =
    requiredPermissions.isEmpty() ||
        isAdmin ||
        tokenPermissions(token).containsAll(requiredPermissions)
