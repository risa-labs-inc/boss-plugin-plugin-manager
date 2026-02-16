package ai.rever.boss.plugin.dynamic.pluginmanager.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Creates its own lightweight SupabaseClient with Postgrest + Realtime installed,
 * using the same anon key embedded in PluginManagerAPIImpl.
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
    private var pluginsChannel: RealtimeChannel? = null
    private var versionsChannel: RealtimeChannel? = null

    /**
     * Connect to Supabase Realtime and subscribe to plugin store changes.
     */
    fun connect() {
        if (supabaseClient != null) return

        try {
            supabaseClient = createSupabaseClient(
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseAnonKey
            ) {
                install(Realtime)
            }

            subscribeToPlugins()
            subscribeToVersions()
        } catch (_: Exception) {
            // Silently fail - manual refresh still works
        }
    }

    private fun subscribeToPlugins() {
        scope.launch {
            try {
                val client = supabaseClient ?: return@launch

                pluginsChannel = client.channel("pm-plugins-changes")
                val changeFlow = pluginsChannel!!.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "plugins"
                }

                pluginsChannel!!.subscribe()

                _isConnected.value = true

                changeFlow.collect { action ->
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
            } catch (_: Exception) {
                _isConnected.value = false
            }
        }
    }

    private fun subscribeToVersions() {
        scope.launch {
            try {
                val client = supabaseClient ?: return@launch

                versionsChannel = client.channel("pm-versions-changes")
                val changeFlow = versionsChannel!!.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "plugin_versions"
                }

                versionsChannel!!.subscribe()

                changeFlow.collect { action ->
                    when (action) {
                        is PostgresAction.Insert -> {
                            val pluginId = action.record["plugin_id"]?.toString()?.removeSurrounding("\"") ?: ""
                            val version = action.record["version"]?.toString()?.removeSurrounding("\"") ?: ""
                            _storeChanges.emit(StoreChangeEvent.VersionAdded(pluginId, version))
                        }
                        else -> {}
                    }
                }
            } catch (_: Exception) {
                // Versions channel failure is non-fatal
            }
        }
    }

    /**
     * Disconnect from Supabase Realtime and clean up resources.
     */
    fun disconnect() {
        scope.launch {
            try {
                pluginsChannel?.unsubscribe()
                versionsChannel?.unsubscribe()
                supabaseClient?.close()
            } catch (_: Exception) {
                // Best-effort cleanup
            } finally {
                supabaseClient = null
                pluginsChannel = null
                versionsChannel = null
                _isConnected.value = false
            }
        }
    }

    /**
     * Dispose of all resources including the coroutine scope.
     */
    fun dispose() {
        disconnect()
        scope.cancel()
    }
}
