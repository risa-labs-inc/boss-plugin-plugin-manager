package ai.rever.boss.plugin.dynamic.pluginmanager.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch

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
 * The subscription runs inside a **reconnect loop**: if the WebSocket drops (laptop sleep/wake, a
 * network change, an idle timeout) the loop tears the client down and re-subscribes with capped
 * exponential backoff, flipping [isConnected] false→true across the gap. Without this a single drop
 * left the toolbox permanently blind to newly published versions until the whole app was restarted.
 * Consumers should re-fetch the catalog when [isConnected] transitions back to true, to catch any
 * change published while the socket was down.
 *
 * Uses [withHostClassLoader] to temporarily swap the thread's context classloader to the parent
 * (host) classloader before creating the SupabaseClient, so Ktor's ServiceLoader can discover the
 * CIO engine and WebSocket plugin from the host's classpath.
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

    private var supabaseClient: SupabaseClient? = null
    private var managerJob: Job? = null

    @Volatile
    private var running = false

    /**
     * Connect to Supabase Realtime and keep the subscription alive across drops. Idempotent — a
     * second call while already running is a no-op.
     */
    fun connect() {
        if (managerJob != null) return
        running = true
        managerJob = scope.launch { runWithReconnect() }
    }

    /** Subscribe, stream changes, and on any failure reconnect with capped exponential backoff. */
    private suspend fun runWithReconnect() {
        var attempt = 0
        while (running) {
            try {
                val client = withHostClassLoader {
                    createSupabaseClient(
                        supabaseUrl = supabaseUrl,
                        supabaseKey = supabaseAnonKey
                    ) {
                        install(Realtime)
                    }
                }
                supabaseClient = client

                // coroutineScope suspends here until a collector ends or throws (i.e. the socket
                // dropped); a throw propagates out and is handled below, triggering a reconnect.
                coroutineScope {
                    val pluginsChannel = client.channel("pm-plugins-changes")
                    val versionsChannel = client.channel("pm-versions-changes")
                    val pluginFlow = pluginsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "plugins"
                    }
                    val versionFlow = versionsChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "plugin_versions"
                    }
                    pluginsChannel.subscribe()
                    versionsChannel.subscribe()

                    _isConnected.value = true
                    attempt = 0 // healthy connection — reset backoff

                    launch { pluginFlow.collect { onPluginAction(it) } }
                    launch { versionFlow.collect { onVersionAction(it) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall through to teardown + backoff + retry.
            }

            _isConnected.value = false
            runCatching { supabaseClient?.close() }
            supabaseClient = null

            if (!running) break
            attempt++
            delay(backoffMillis(attempt))
        }
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

    /** 1s, 2s, 4s … capped at [MAX_BACKOFF_MS]. */
    private fun backoffMillis(attempt: Int): Long {
        val step = (attempt - 1).coerceIn(0, 5)
        return (BASE_BACKOFF_MS shl step).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Stop the reconnect loop and disconnect from Supabase Realtime.
     */
    fun disconnect() {
        running = false
        managerJob?.cancel()
        managerJob = null
        scope.launch {
            runCatching { supabaseClient?.close() }
            supabaseClient = null
            _isConnected.value = false
        }
    }

    /**
     * Dispose of all resources including the coroutine scope. [disconnect] launches the (suspending)
     * client close on [scope] as best-effort cleanup before the scope is cancelled.
     */
    fun dispose() {
        disconnect()
        scope.cancel()
    }

    private companion object {
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}

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
