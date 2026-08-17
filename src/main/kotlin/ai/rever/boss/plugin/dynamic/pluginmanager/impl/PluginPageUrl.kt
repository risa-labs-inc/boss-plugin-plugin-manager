package ai.rever.boss.plugin.dynamic.pluginmanager.impl

/**
 * The web address of a plugin's page.
 *
 * The page lives under the organisation that owns the plugin - `/o/<slug>/plugins/<id>` - so a
 * plugin with no known organisation has no page, and this returns null rather than a URL that
 * would 404. That is the normal answer for a sideloaded jar: an installed plugin is known from
 * `installed.json` and the jar on disk, and neither records an organisation.
 *
 * NO SESSION IS NEEDED to read the page for a public plugin, which is what makes linking to it
 * worth doing at all. Handing a session to those pages needs a members-only handoff token, so a
 * link that required one would work only for plugins owned by an organisation the reader had
 * joined - almost none of the store, for almost every reader.
 */
object PluginPageUrl {
    /**
     * Same host the store API is read from. Kept here rather than shared with
     * [PluginManagerAPIImpl] because that one is a private constant of a class this file has no
     * reason to depend on, and one string is cheaper than the coupling.
     */
    const val ORGANISATION_FUNCTION_BASE: String = "https://api.risaboss.com/functions/v1/organisation"

    /**
     * The page for [pluginId] under [orgSlug], or null when either is missing.
     *
     * Both are percent-encoded into the path. Neither needs it today - slugs match
     * `^[a-z][a-z0-9_]{1,30}$` and plugin ids are dotted reverse-DNS - and that is exactly why it
     * is done here rather than trusted: nothing in the store schema constrains `plugin_id` to that
     * shape, and a `?` or `#` in one would silently truncate the path and open a different page.
     */
    fun forPlugin(
        orgSlug: String,
        pluginId: String,
        base: String = ORGANISATION_FUNCTION_BASE,
    ): String? {
        if (orgSlug.isBlank() || pluginId.isBlank()) return null
        return "$base/o/${encodePathSegment(orgSlug)}/plugins/${encodePathSegment(pluginId)}"
    }

    /**
     * Percent-encode one path segment.
     *
     * Written out rather than using `URLEncoder`, which is a FORM encoder: it turns a space into
     * `+`, which in a path is a literal plus and not a space. The unreserved set is RFC 3986's.
     */
    private fun encodePathSegment(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val ch = byte.toInt().toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' ||
                ch == '_' || ch == '~'
            ) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
