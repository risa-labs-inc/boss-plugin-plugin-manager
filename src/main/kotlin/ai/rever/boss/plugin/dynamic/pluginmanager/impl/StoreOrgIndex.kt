package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem

/**
 * The organisations present in the catalogue, for the filter control to offer.
 *
 * Derived rather than hardcoded: the set of organisations publishing to this store is not
 * something the client knows, and a fixed list would go stale the first time somebody new
 * publishes. Sorted so the control does not reorder itself between refreshes, which is what
 * happens when the underlying list order changes and the chips follow it.
 */
fun storeOrgSlugs(items: List<PluginStoreItem>): List<String> =
    items.asSequence()
        .map { it.orgSlug }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .toList()

/**
 * Narrow the organisation list by what was typed into the picker.
 *
 * Substring, not prefix: an organisation slug is often a compound like `risa_labs`, and somebody
 * looking for it types the part they remember rather than the part it starts with.
 *
 * The leading `@` is stripped from the query, because the control displays slugs as `@risa` and
 * typing what you see must work. Case is folded for the same reason - slugs are lowercase by
 * their CHECK constraint, but nothing tells the person typing that.
 *
 * A blank query returns everything rather than nothing, so opening the picker shows the full list.
 */
fun filterOrgSlugs(
    slugs: List<String>,
    query: String,
): List<String> {
    val needle = query.trim().removePrefix("@").lowercase()
    if (needle.isEmpty()) return slugs
    return slugs.filter { it.lowercase().contains(needle) }
}

/**
 * Does a plugin pass the current organisation filter?
 *
 * A null [filter] is "no filter" and passes everything, including plugins with no known
 * organisation. A SET filter excludes them: with `@risa` selected, a sideloaded plugin the store
 * has never heard of is not a risa plugin, and showing it because its organisation is unknown
 * would make the filter mean "risa, plus anything I could not classify".
 *
 * [orgSlug] is nullable rather than defaulted because the three call sites source it differently:
 * the store list has it on the item, while Installed and Updates look it up in a map that may miss.
 */
fun matchesOrgFilter(
    orgSlug: String?,
    filter: String?,
): Boolean = filter == null || orgSlug == filter

/**
 * Where a plugin came from: who wrote it, and which organisation owns it.
 *
 * One value rather than two parallel maps, because the two are always rendered together and
 * always come from the same catalogue row. Two maps invite a call site that has the author and not
 * the organisation, which is how the three tabs drifted apart in the first place.
 */
data class StoreProvenance(
    val author: String,
    val orgSlug: String,
)

/**
 * Provenance by plugin id, for the store catalogue.
 *
 * Pulled out of the composable that used to build it inline so the rule can be asserted. It is the
 * one place three tabs agree on where a plugin came from, and each case below renders a line that
 * is either absent or wrong - neither of which a compile catches.
 *
 * A plugin with NEITHER an author nor an organisation is OMITTED. One of the two is enough to be
 * worth an entry: the store knows plenty of plugins whose `author_name` is blank, and dropping
 * those would lose their organisation badge too.
 */
fun storeProvenanceByPluginId(items: List<PluginStoreItem>): Map<String, StoreProvenance> =
    items
        .asSequence()
        .filter { it.pluginId.isNotEmpty() }
        .filter { it.author.isNotEmpty() || it.orgSlug.isNotEmpty() }
        // A duplicate plugin id should not happen - `plugins.plugin_id` is unique - but associate
        // would silently keep the LAST occurrence, and a catalogue that somehow served two rows for
        // one id would then depend on server ordering for what is shown. First wins, matching the
        // order the store returned.
        .distinctBy { it.pluginId }
        .associate { it.pluginId to StoreProvenance(author = it.author, orgSlug = it.orgSlug) }
