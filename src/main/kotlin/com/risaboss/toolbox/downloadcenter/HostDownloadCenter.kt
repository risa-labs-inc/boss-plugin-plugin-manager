package com.risaboss.toolbox.downloadcenter

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TransferHandle
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's download center, if this host has one.
 *
 * **This package is outside `ai.rever.boss.plugin.*` on purpose, and moving it
 * back would break every older host.** Two host mechanisms make that a hard
 * requirement rather than a preference:
 *
 * - `BinaryCompatibilityValidator` walks the constant pool of every
 *   `ai.rever.boss.plugin.*` class in the jar and rejects the WHOLE plugin if a
 *   referenced api class or member cannot be resolved. So a single
 *   `DownloadCenterProvider` reference from the plugin's own package would make
 *   this build refuse to load on a host predating it - not degrade, refuse. The
 *   validator skips classes outside that package exactly so optional adapters
 *   like this one can exist.
 * - `PluginContext` is host-compiled and served parent-first, so on an older
 *   host `downloadCenterProvider` does not exist and reading it raises
 *   `NoSuchMethodError`. A `?:` cannot help with that; a `catch (LinkageError)`
 *   can, and has to be on the far side of the package boundary above.
 *
 * The consequence for callers: reach the center only through [createOrNull], and
 * keep api types out of anything that crosses back.
 */
object HostDownloadCenter {
    /**
     * A reporter backed by the host's center, or null when this host has none.
     *
     * Null means one of three things and they are handled identically: an older
     * host with no such property, a host whose api layer predates the types, or a
     * host that simply returns null. In every case the caller falls back to the
     * plugin's own status-bar widget.
     */
    fun createOrNull(
        context: PluginContext,
        scope: CoroutineScope,
    ): TransferReporter? =
        try {
            context.downloadCenterProvider?.let { HostCenterReporter(it, scope) }
        } catch (_: LinkageError) {
            // Older host: the property, or the types behind it, are not there.
            null
        }
}

/**
 * Forwards this plugin's reports to the host center, and reads back which
 * plugins the host says are busy.
 *
 * Internal rather than private so a test can drive it with a fake provider: reaching
 * it through [HostDownloadCenter.createOrNull] would mean standing up a whole
 * PluginContext for a class that only wants one of its properties.
 *
 * Ownership is decided HERE rather than taken from the host: `begin` hands back a
 * handle either way, and the plugin needs to know whether it created the row so
 * that only the owner ends it. `putIfAbsent` answers that locally, which also
 * means a nested call cannot take the progress channel from the call that owns it.
 */
internal class HostCenterReporter(
    private val provider: ai.rever.boss.plugin.api.DownloadCenterProvider,
    scope: CoroutineScope,
) : TransferReporter {
    private val handles = ConcurrentHashMap<String, TransferHandle>()

    override val busyIds: StateFlow<Set<String>> =
        provider.transfers
            .map { transfers ->
                transfers
                    .filter { it.kind == TransferKind.PLUGIN_INSTALL || it.kind == TransferKind.PLUGIN_UPDATE }
                    .map { it.id }
                    .toSet()
            }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * NOTE: `provider.begin` runs inside `computeIfAbsent`, so it must not re-enter
     * this reporter for the same key on the calling thread - `ConcurrentHashMap`
     * documents that a mapping function must not update the same map, and the failure
     * would be a deadlock rather than something visible. It does not today (the host
     * publishes into a StateFlow that nothing here collects synchronously); a listener
     * added later that calls back into `begin`/`end` would.
     */
    override fun begin(
        key: String,
        title: String,
        isUpdate: Boolean,
        onCancel: () -> Unit,
    ): Boolean {
        var created = false
        // Ownership decided BEFORE the host is called, and by one thread. Calling
        // begin() first and deciding afterwards had two concurrent callers each get a
        // handle, and whichever won putIfAbsent kept ITS handle - which may be the
        // non-owning one, whose done() is a no-op by contract. The row then never
        // closed, and because busyIds is derived from the host's transfers, that
        // plugin's buttons read busy for the rest of the session.
        handles.computeIfAbsent(key) {
            created = true
            provider.begin(
                id = key,
                title = title,
                kind = if (isUpdate) TransferKind.PLUGIN_UPDATE else TransferKind.PLUGIN_INSTALL,
                onCancel = onCancel,
            )
        }
        return created
    }

    override fun progress(
        key: String,
        fraction: Float,
    ) {
        handles[key]?.progress(fraction)
    }

    override fun downloading(key: String) {
        handles[key]?.phase(TransferPhase.DOWNLOADING)
    }

    override fun installing(key: String) {
        handles[key]?.phase(TransferPhase.INSTALLING)
    }

    override fun end(key: String) {
        handles.remove(key)?.done()
    }
}
