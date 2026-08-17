package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem

/**
 * Owning organisation slug, by plugin id, for the store catalogue.
 *
 * Pulled out of the composable that used to build it inline so the rule can be asserted. It is the
 * one place three tabs agree on what organisation a plugin belongs to, and each of the cases below
 * renders a badge that is either absent or wrong - neither of which a compile catches.
 *
 * A plugin with no known organisation is OMITTED rather than mapped to an empty string. The
 * difference matters at the call site: `map[id].orEmpty()` then yields "" for both "not in the
 * store" and "in the store with no organisation", and both must render nothing. Keeping the entry
 * out means the map's size is the number of plugins that actually have an organisation, which is
 * what makes it worth logging or counting later.
 */
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

fun storeOrgSlugsByPluginId(items: List<PluginStoreItem>): Map<String, String> =
    items
        .asSequence()
        .filter { it.pluginId.isNotEmpty() && it.orgSlug.isNotEmpty() }
        // A duplicate plugin id should not happen - `plugins.plugin_id` is unique - but associate
        // would silently keep the LAST occurrence, and a catalogue that somehow served two rows for
        // one id would then depend on server ordering for which organisation is shown. First wins,
        // matching the order the store returned.
        .distinctBy { it.pluginId }
        .associate { it.pluginId to it.orgSlug }
