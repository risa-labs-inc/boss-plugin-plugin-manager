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
 * [isConnected] therefore mirrors the two channels' own join state. The library marks channels
 * `UNSUBSCRIBED` while the socket is down and re-subscribes them on reconnect, so the flag flips
 * false->true across a drop. Consumers should re-fetch the catalog on that transition to pick up
 * anything published while the socket was gone.
 *
 * The retry loop here covers only *setup* failure (client or channel construction), which is the
 * one thing that genuinely throws. It is bounded: those failures are configuration or classloader
 * problems that fail identically on every attempt, so retrying forever would only burn a wakeup
 * every 30s with nothing to show for it. [lastError] records why setup gave up.
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
     * Why realtime is not running, when it gave up. Null while healthy. Exposed because a silent
     * `catch` is what made the original staleness bug expensive to diagnose.
     */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * The subscription coroutine. Sole owner of the [SupabaseClient]: it creates it and closes it
     * in a `finally`, so no other thread can close a client out from under it.
     */
    @Volatile
    private var managerJob: Job? = null

    /**
     * Connect to Supabase Realtime and keep the subscription alive. Idempotent - a second call
     * while already running is a no-op.
     *
     * Synchronized with [disconnect] so the check-then-launch cannot interleave with a teardown
     * and leave a started job unreferenced, or two jobs racing for the same channel topics.
     */
    @Synchronized
    fun connect() {
        if (managerJob != null) return
        managerJob = scope.launch { run() }
    }

    /**
     * Create the client, stream changes, and retry a bounded number of times if *setup* fails.
     * Cancellation propagates, but the client is still closed on the way out: the `finally` runs
     * on the cancellation path too, which is what keeps a plugin reload from leaking a live
     * WebSocket plus the Ktor engine's thread pool.
     */
    private suspend fun run() {
        var attempt = 0
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
                _lastError.value = null
                // Never returns: the channel-status and change-flow collectors do not complete.
                stream(client)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _lastError.value = e.message ?: e::class.simpleName ?: "realtime setup failed"
                attempt++
            } finally {
                _isConnected.value = false
                // NonCancellable so the close still completes when we got here by cancellation.
                withContext(NonCancellable) { runCatching { client?.close() } }
            }
            if (attempt >= MAX_SETUP_ATTEMPTS) return
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

        // Collect before subscribing: a change flow registers its callback when collection
        // starts, so joining first would drop anything that lands during the join round-trip.
        // The consumer-side re-sync on the false->true transition covers that window regardless.
        launch { pluginFlow.collect { onPluginAction(it) } }
        launch { versionFlow.collect { onVersionAction(it) } }

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
     * suspends, and the two obvious ways to run it here are both wrong: launching it on [scope]
     * loses the race with [dispose]'s `scope.cancel()`, and letting it outlive this call means an
     * async teardown still touching Ktor after the plugin classloader has closed.
     *
     * The wait is bounded because this sits on the plugin-unload path. `withTimeoutOrNull` is
     * scheduled on `runBlocking`'s own event loop, so the bound holds even if the job never gets
     * a dispatcher thread - a saturated [Dispatchers.Default] costs a pause, never a deadlock.
     */
    @Synchronized
    fun disconnect() {
        val job = managerJob ?: return
        managerJob = null
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
         * Setup failures are deterministic (bad config, or Ktor's engine missing from the
         * classloader), so give up rather than retry forever. [lastError] holds the reason.
         */
        const val MAX_SETUP_ATTEMPTS = 5

        /**
         * Upper bound on how long plugin unload waits for the socket to close. Closing a
         * WebSocket is a matter of milliseconds; this only has to be generous enough not to
         * abandon a healthy close, and short enough not to read as a hang if one wedges.
         */
        const val CLOSE_TIMEOUT_MS = 1_500L
    }
}

/** Backoff for setup retries: 1s, 2s, 4s ... capped at 30s. `attempt` is 1-based. */
internal fun backoffMillis(attempt: Int): Long {
    val step = (attempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
    return (BASE_BACKOFF_MS shl step).coerceAtMost(MAX_BACKOFF_MS)
}

private const val BASE_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 30_000L
private const val MAX_BACKOFF_SHIFT = 5

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
