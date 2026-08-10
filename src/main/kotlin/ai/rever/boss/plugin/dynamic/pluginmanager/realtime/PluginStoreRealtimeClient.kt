package ai.rever.boss.plugin.dynamic.pluginmanager.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Events emitted when plugin store data changes via Supabase Realtime.
 */
sealed class StoreChangeEvent {
    data class PluginChanged(val pluginId: String, val action: String) : StoreChangeEvent()
    data class VersionAdded(val pluginId: String, val version: String) : StoreChangeEvent()
}

/**
 * Client that subscribes to Supabase Realtime for plugin store changes.
 *
 * **Socket reconnection belongs to supabase-kt, not to us.** The library detects a dead socket
 * (an unacknowledged heartbeat, or a failure in its message loop) and runs its own
 * disconnect/connect cycle that re-joins every channel. What was missing - and what left the
 * catalog stale until an app restart - is an accurate liveness signal: [isConnected] used to be
 * derived from a collector on [postgresChangeFlow], which is a `callbackFlow` that neither
 * completes nor throws when the socket dies, so the flag was set once and never moved again.
 *
 * [isConnected] therefore mirrors the two channels' own join state. `RealtimeImpl.disconnect()`
 * marks every channel `UNSUBSCRIBED`, and its `reconnect()` is `disconnect()` then `connect()`,
 * so the flag flips false->true across a drop. Consumers should re-fetch the catalog on that
 * transition to pick up anything published while the socket was gone.
 *
 * Note the lag: the flag drops when the library *notices* the socket is dead (an unacked heartbeat,
 * so up to about two 15s beats), not the instant it dies. Anything keyed off "currently
 * disconnected" is therefore approximate. The false->true edge is not, and it survives even if the
 * library ever stops marking channels down on disconnect, because a rejoin passes through
 * `SUBSCRIBING` either way.
 *
 * The retry loop here covers only construction failure (the client or the channels), which is the
 * one thing on this path that genuinely throws. Everything after that is the library's business.
 *
 * Uses [withHostClassLoader] to temporarily swap the thread's context classloader to the parent
 * (host) classloader before creating the SupabaseClient, so Ktor's ServiceLoader can discover the
 * CIO engine and WebSocket plugin from the host's classpath. Only client creation needs this: the
 * library's own reconnect reuses the already-built `HttpClient` and does no ServiceLoader lookup.
 */
class PluginStoreRealtimeClient(
    private val supabaseUrl: String,
    private val supabaseAnonKey: String
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _storeChanges = MutableSharedFlow<StoreChangeEvent>()
    val storeChanges: SharedFlow<StoreChangeEvent> = _storeChanges.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    /**
     * The subscription coroutine. Sole owner of the [SupabaseClient]: it creates it and closes it
     * in a `finally`, so no other thread can close a client out from under it.
     *
     * Cleared when the job completes, so [connect] is callable again. Holding a completed job here
     * would make the idempotence guard permanent: realtime would stay dead for the session with no
     * path back. Atomic rather than monitor-guarded because the completion handler runs on whatever
     * thread finishes the job, and [disconnect] holds this instance's monitor while it joins.
     */
    private val managerJob = AtomicReference<Job?>(null)

    /**
     * Connect to Supabase Realtime and keep the subscription alive. Idempotent - a second call
     * while a subscription is live is a no-op, but a call after one has ended does restart it.
     *
     * Synchronized with [disconnect] so the check-then-launch cannot interleave with a teardown
     * and leave a started job unreferenced, or two jobs racing for the same channel topics.
     */
    @Synchronized
    fun connect() {
        if (managerJob.get()?.isActive == true) return
        val job = scope.launch { run() }
        managerJob.set(job)
        job.invokeOnCompletion { managerJob.compareAndSet(job, null) }
    }

    /**
     * Create the client, stream changes, and retry with capped backoff if construction throws.
     * Cancellation propagates, but the client is still closed on the way out: the `finally` runs
     * on the cancellation path too, which is what keeps a plugin reload from leaking a live
     * WebSocket plus the Ktor engine's thread pool.
     *
     * Retries are unbounded on purpose. Giving up after N attempts sounds tidy for what should be
     * deterministic config failures, but this `catch` is wider than that - anything thrown while
     * building the channels lands here too, including a transient failure that a later attempt
     * would get past. A wakeup every 30s costs nothing next to needing an app restart.
     *
     * `attempt` is never reset. A loop that keeps going round is failing, not recovering, and
     * resetting on each pass would hold it at a flat one-second retry forever.
     */
    private suspend fun run() {
        var attempt = 0
        var reportedFailure = false
        while (true) {
            var client: SupabaseClient? = null
            try {
                client = withHostClassLoader {
                    createSupabaseClient(
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseAnonKey
                    ) {
                        install(Realtime)
                    }
                }
                stream(client)
                // stream() is not supposed to return: its collectors do not complete. That is a
                // claim about library internals, and this whole class exists because one such
                // claim turned out to be wrong - so treat an unexpected return as a drop and
                // reconnect, rather than ending the subscription for the session. A deliberate
                // teardown is still distinguishable: disconnect() cancels, which rethrows above.
                attempt++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!reportedFailure) {
                    reportedFailure = true
                    // No logger is available to plugins, and a fully silent retry loop is what
                    // made the original staleness expensive to diagnose. One line, first failure
                    // only, so a permanent failure does not turn into a 30s-interval spam loop.
                    System.err.println(
                        "[plugin-manager] realtime setup failed, retrying: " +
                            "${e::class.simpleName}: ${e.message}"
                    )
                }
                attempt++
            } finally {
                _isConnected.value = false
                // NonCancellable so the close still completes when we got here by cancellation.
                withContext(NonCancellable) { runCatching { client?.close() } }
            }
            delay(backoffMillis(attempt))
        }
    }

    /** Subscribe both channels and stream their changes for as long as the client lives. */
    private suspend fun stream(client: SupabaseClient) = coroutineScope {
        val pluginsChannel = client.channel(PLUGINS_TOPIC)
        val versionsChannel = client.channel(VERSIONS_TOPIC)
        // The change flows must be built before subscribing: creating one registers the
        // postgres_changes config that goes out in the join payload, and the library rejects the
        // call outright once the channel has joined.
        val pluginFlow = pluginsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "plugins"
        }
        val versionFlow = versionsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "plugin_versions"
        }

        launch {
            combine(pluginsChannel.status, versionsChannel.status) { plugins, versions ->
                plugins == RealtimeChannel.Status.SUBSCRIBED &&
                    versions == RealtimeChannel.Status.SUBSCRIBED
            }.collect { _isConnected.value = it }
        }

        // Collect before subscribing: a change flow registers its callback when collection starts,
        // so joining first would drop anything that lands during the join round-trip. UNDISPATCHED
        // is what makes that ordering true rather than merely intended - the default only
        // *schedules* the child, so on a multi-threaded dispatcher subscribe() can win the race.
        launch(start = CoroutineStart.UNDISPATCHED) { pluginFlow.collect { onPluginAction(it) } }
        launch(start = CoroutineStart.UNDISPATCHED) { versionFlow.collect { onVersionAction(it) } }

        pluginsChannel.subscribe()
        versionsChannel.subscribe()
    }

    private suspend fun onPluginAction(action: PostgresAction) {
        when (action) {
            is PostgresAction.Insert -> {
                val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                _storeChanges.emit(StoreChangeEvent.PluginChanged(pluginId, "INSERT"))
            }
            is PostgresAction.Update -> {
                val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                _storeChanges.emit(StoreChangeEvent.PluginChanged(pluginId, "UPDATE"))
            }
            is PostgresAction.Delete -> {
                val pluginId = action.oldRecord["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                _storeChanges.emit(StoreChangeEvent.PluginChanged(pluginId, "DELETE"))
            }
            else -> {}
        }
    }

    private suspend fun onVersionAction(action: PostgresAction) {
        if (action is PostgresAction.Insert) {
            val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
            val version = action.record["version"]?.toString()?.removeSurrounding("\"") ?: ""
            _storeChanges.emit(StoreChangeEvent.VersionAdded(pluginId, version))
        }
    }

    /**
     * Stop the subscription and close the client, leaving the scope usable so [connect] can start
     * a fresh one.
     *
     * Blocks briefly on the manager job's teardown rather than deferring it. `SupabaseClient.close()`
     * suspends, and launching it on [scope] loses the race with [dispose]'s `scope.cancel()` - the
     * coroutine is cancelled before its first statement, so the close never happens at all.
     *
     * The wait is bounded because this sits on the plugin-unload path. `withTimeoutOrNull` is
     * scheduled on `runBlocking`'s own event loop, so the bound holds even if the job never gets
     * a dispatcher thread - a saturated [Dispatchers.Default] costs a pause, never a deadlock.
     * **This blocks the calling thread for up to [CLOSE_TIMEOUT_MS].** On the timeout path the
     * teardown is abandoned mid-flight and can still outlive this call, which is the thing the
     * bound trades away: a hung close should not wedge unload.
     */
    @Synchronized
    fun disconnect() {
        val job = managerJob.getAndSet(null) ?: return
        job.cancel()
        runCatching { runBlocking { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { job.join() } } }
        _isConnected.value = false
    }

    /** Dispose of all resources including the coroutine scope. Terminal - [connect] will not restart. */
    fun dispose() {
        disconnect()
        scope.cancel()
    }

    private companion object {
        const val PLUGINS_TOPIC = "pm-plugins-changes"
        const val VERSIONS_TOPIC = "pm-versions-changes"

        /**
         * Upper bound on how long plugin unload waits for the socket to close. Closing a
         * WebSocket is a matter of milliseconds; this only has to be generous enough not to
         * abandon a healthy close, and short enough not to read as a hang if one wedges.
         */
        const val CLOSE_TIMEOUT_MS = 1_500L
    }
}

/** Backoff for retries: 1s, 2s, 4s ... capped at 30s. `attempt` is 1-based. */
internal fun backoffMillis(attempt: Int): Long {
    // The shift is clamped only to stop `shl` wrapping - Kotlin masks the distance to 6 bits, so
    // an unclamped `1000L shl 64` is 1000L again, turning the cap into a one-second busy loop.
    // The cap itself is coerceAtMost, so this bound just has to be past where the cap bites.
    val step = (attempt - 1).coerceIn(0, MAX_SAFE_SHIFT)
    return (BASE_BACKOFF_MS shl step).coerceAtMost(MAX_BACKOFF_MS)
}

/**
 * Whether an [isConnected] emission is a *re*connect worth re-fetching the catalog for.
 *
 * The trigger is a true that follows a false, which is why [previous] is nullable: realtime is
 * connected at plugin load, long before a panel opens, so a panel opening later sees `true` as its
 * first emission. Treating that as an edge duplicates the initial load's own fetch on every open.
 */
internal fun shouldResync(previous: Boolean?, connected: Boolean): Boolean =
    connected && previous == false

private const val BASE_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val MAX_SAFE_SHIFT = 16

/**
 * Execute a block with the host (parent) classloader as the thread's context classloader.
 *
 * Sandboxed plugins run with their own classloader, but Ktor uses
 * [java.util.ServiceLoader] with [Thread.contextClassLoader] to discover
 * HTTP engines and WebSocket plugins. The parent classloader (host) has
 * these registered in META-INF/services, so we temporarily swap to it.
 */
internal inline fun <T> withHostClassLoader(block: () -> T): T {
    val current = Thread.currentThread().contextClassLoader
    return try {
        Thread.currentThread().contextClassLoader = current.parent ?: current
        block()
    } finally {
        Thread.currentThread().contextClassLoader = current
    }
}
