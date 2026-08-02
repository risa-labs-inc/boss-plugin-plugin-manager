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
const val PLUGIN_CREATE_PERMISSION = "plugins.create"

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
        // JWTs use base64url without padding; Base64.getDecoder() requires both
        // the standard alphabet and correct padding.
        val payload = parts[1].replace("-", "+").replace("_", "/")
        val padded = when (payload.length % 4) {
            2 -> "$payload=="
            3 -> "$payload="
            else -> payload
        }
        val decoded = String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        val perms = permissionJson.parseToJsonElement(decoded).jsonObject["user_permissions"]?.jsonArray
            ?: return emptySet()
        perms.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}
