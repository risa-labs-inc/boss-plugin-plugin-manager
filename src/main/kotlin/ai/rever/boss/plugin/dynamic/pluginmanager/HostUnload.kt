package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.plugin.api.PluginUnloadResult

/**
 * Asking the host to unload a plugin, saying why, on whatever host is actually running.
 *
 * Updating a plugin here is an uninstall followed by a reinstall, so the host's refusal to
 * unload something another plugin depends on landed on the **Update** button - the AI Gateway
 * could not be updated at all while any of its consumers was loaded. api 1.0.79 replaced the
 * blanket refusal with a prompt, and gave the unload an intent so the host can word that prompt
 * and time the dependents' restart. The refusal reasons, which name the plugins in the way, come
 * back with it instead of dying at the old `Boolean`.
 *
 * ### Why this is its own file, and why it catches [LinkageError]
 *
 * [PluginLoaderDelegate] is implemented by the **host**, and `plugin-api-core` filters it into
 * the host build and serves it parent-first. So on a BOSS built against an api pin older than
 * 1.0.79, this plugin's `INVOKEINTERFACE` resolves against the host's own older copy of the
 * interface, which has no such method: `NoSuchMethodError` at the call site, and
 * `NoClassDefFoundError` for the new api types if the installed api jar is older too. Neither is
 * an `Exception`, so an ordinary `catch (e: Exception)` would not hold them.
 *
 * Both are thrown while *linking the method that mentions the missing symbols*, which is why the
 * call lives in its own function: verification failure takes out [unloadForIntent] and nothing
 * else, leaving [unloadWithIntent] to fall back to the pre-1.0.79 verb. Inlining it would take
 * the caller down with it.
 *
 * `BossApiRuntime.isAtLeast` is deliberately **not** the gate. It reports the level of the
 * installed api *jar*, and the jar is not what this call resolves against - a host that has not
 * been rebuilt can be running a newer jar and still not have the method.
 */
object HostUnload {
    /**
     * Unloads [pluginId], telling the host it is [intent], and degrading on an older host.
     *
     * On the fallback path the host still prompts - `unloadPlugin` routes through the same code
     * with an unspecified intent from 1.0.79 on - so this loses the wording and the reasons,
     * not the feature. On a host older than that, it is exactly the behaviour that shipped.
     */
    suspend fun unloadWithIntent(
        delegate: PluginLoaderDelegate,
        pluginId: String,
        intent: PluginUnloadIntent,
    ): PluginUnloadResult =
        runCatching { unloadForIntent(delegate, pluginId, intent) }
            .getOrElse { unloadLegacy(delegate, pluginId) }

    /**
     * The 1.0.79 call, alone in a function so that a link failure is contained to it.
     *
     * `runCatching` in the caller catches `Throwable`, which is the point: the failures this
     * guards against are `Error`s, not `Exception`s.
     */
    private suspend fun unloadForIntent(
        delegate: PluginLoaderDelegate,
        pluginId: String,
        intent: PluginUnloadIntent,
    ): PluginUnloadResult = delegate.unloadPluginForIntent(pluginId, intent)

    /**
     * The pre-1.0.79 verb. A bare `Boolean`, so a decline and a refusal are indistinguishable -
     * both come back as an unexplained failure, which is what this whole file exists to stop
     * being the only answer available.
     */
    private suspend fun unloadLegacy(
        delegate: PluginLoaderDelegate,
        pluginId: String,
    ): PluginUnloadResult = PluginUnloadResult(unloaded = delegate.unloadPlugin(pluginId))
}
