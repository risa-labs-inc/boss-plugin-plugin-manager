package ai.rever.boss.plugin.dynamic.pluginmanager.realtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Events emitted when plugin store data changes.
 */
sealed class StoreChangeEvent {
    data class PluginChanged(val pluginId: String, val action: String) : StoreChangeEvent()
    data class VersionAdded(val pluginId: String, val version: String) : StoreChangeEvent()
}

/**
 * Stub client for plugin store realtime updates.
 *
 * Supabase Realtime cannot be used directly from a sandboxed plugin classloader
 * because the Supabase SDK's internal Ktor HttpClient installs ContentNegotiation
 * which intercepts WebSocket upgrade responses before the WebSocket plugin
 * (not discoverable via ServiceLoader in the sandbox) can handle them.
 *
 * TODO: Implement via host's PluginLoaderDelegate to share the host's
 * existing Realtime infrastructure (PluginStoreRealtimeService).
 */
class PluginStoreRealtimeClient(
    private val supabaseUrl: String,
    private val supabaseAnonKey: String
) {
    private val _storeChanges = MutableSharedFlow<StoreChangeEvent>()
    val storeChanges: SharedFlow<StoreChangeEvent> = _storeChanges.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun connect() {
        // No-op: Realtime not available in sandboxed plugin classloader.
        // Manual refresh via Postgrest still works.
    }

    fun disconnect() {
        _isConnected.value = false
    }

    fun dispose() {
        disconnect()
    }
}
