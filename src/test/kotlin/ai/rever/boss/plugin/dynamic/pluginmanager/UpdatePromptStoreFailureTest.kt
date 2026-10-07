package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import com.risaboss.toolbox.downloadcenter.TransferReporter
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real Postgrest responses pass through the API and production prompt service. */
class UpdatePromptStoreFailureTest {
    private class Fixture {
        val directory = Files.createTempDirectory("prompt-store-failure").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failingTable: String? = null
        val requests = mutableListOf<String>()
        val client = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            httpEngine = MockEngine { request ->
                val table = request.url.encodedPath.substringAfterLast('/')
                requests += table
                if (table == failingTable) {
                    respond("""{"message":"Store unavailable"}""", HttpStatusCode.ServiceUnavailable,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    val body = when (table) {
                        "plugins_with_latest_version" -> """[
                            {"plugin_id":"a","latest_version":"2"},
                            {"plugin_id":"b","latest_version":"2"}]
                        """.trimIndent()
                        "plugins" -> """[
                            {"id":"uuid-a","plugin_id":"a","display_name":"A"},
                            {"id":"uuid-b","plugin_id":"b","display_name":"B"}]
                        """.trimIndent()
                        "plugin_versions" -> """[
                            {"id":"version-a","plugin_id":"uuid-a","version":"2"},
                            {"id":"version-b","plugin_id":"uuid-b","version":"2"}]
                        """.trimIndent()
                        else -> error("Unexpected store table: $table")
                    }
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            install(Postgrest) { maxRetries = 0 }
        }
        private val loader = Proxy.newProxyInstance(PluginLoaderDelegate::class.java.classLoader,
            arrayOf(PluginLoaderDelegate::class.java)) { _, method, _ ->
            when (method.name) {
                "getLoadedPlugins" -> listOf(LoadedPluginInfo("a", "A", "1"), LoadedPluginInfo("b", "B", "1"))
                "getPluginsDirectory" -> directory.path
                else -> error("Unexpected loader call: ${method.name}")
            }
        } as PluginLoaderDelegate
        private val reporter = object : TransferReporter {
            override val busyIds = MutableStateFlow<Set<String>>(emptySet())
            override fun begin(key: String, title: String, isUpdate: Boolean, onCancel: () -> Unit) = true
            override fun progress(key: String, fraction: Float) = Unit
            override fun downloading(key: String) = Unit
            override fun installing(key: String) = Unit
            override fun end(key: String) = Unit
        }
        val api = PluginManagerAPIImpl(scope, loader, reporter, client)
        suspend fun close() { scope.cancel(); client.close(); directory.deleteRecursively() }
    }

    private class Notes : NotificationProvider {
        val shown = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        override fun showToast(message: String, type: NotificationType, duration: NotificationDuration,
            title: String?, actionLabel: String?, onAction: (() -> Unit)?): String {
            shown += message
            return "toast-${shown.size}"
        }
        override fun dismiss(notificationId: String) { dismissed += notificationId }
        override fun dismissAll() = Unit
    }

    @Test
    fun `actual compatible update pipeline preserves candidate query failures`(): Unit = runBlocking {
        verifyApiFailure("plugins_with_latest_version")
    }

    @Test
    fun `actual compatible update pipeline preserves compatibility lookup failures`(): Unit = runBlocking {
        for (table in listOf("plugins", "plugin_versions")) verifyApiFailure(table)
    }

    private suspend fun verifyApiFailure(table: String) {
        val fixture = Fixture()
        try {
            fixture.api.refreshInstalledPlugins()
            assertEquals(2, fixture.api.checkForCompatibleUpdatesResult().getOrThrow().size)
            fixture.failingTable = table
            assertTrue(fixture.api.checkForCompatibleUpdatesResult().isFailure)
            assertTrue(table in fixture.requests)
            assertEquals(emptyList(), fixture.api.checkForCompatibleUpdates(), "Legacy list API remains fail-open")
        } finally { fixture.close() }
    }

    @Test
    fun `actual candidate query failure preserves the existing mixed prompt and records`(): Unit = runBlocking {
        verifyPromptFailure("plugins_with_latest_version")
    }

    @Test
    fun `actual compatibility lookup failure preserves the existing mixed prompt and records`(): Unit = runBlocking {
        for (table in listOf("plugins", "plugin_versions")) verifyPromptFailure(table)
    }

    private suspend fun verifyPromptFailure(table: String) {
        val fixture = Fixture()
        try {
            fixture.api.refreshInstalledPlugins()
            val notes = Notes()
            var records: String? = null
            val storage = Proxy.newProxyInstance(PluginStorageProvider::class.java.classLoader,
                arrayOf(PluginStorageProvider::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getJson" -> records
                    "putJson" -> { records = arguments!![1] as String; Unit }
                    else -> error("Unexpected storage call: ${method.name}")
                }
            } as PluginStorageProvider
            var policy = HostAutomaticUpdatePolicy(false, emptySet())
            val service = UpdatePromptService(fixture.scope, fixture.api, null, notes, storage, { policy })
            service.checkAndPrompt()
            assertEquals(listOf("A, B"), notes.shown)
            val originalRecords = records
            policy = HostAutomaticUpdatePolicy(true, setOf("a"))
            fixture.failingTable = table
            service.checkAndPrompt()
            assertEquals(emptyList(), notes.dismissed, "Failed checks must preserve the mixed offer")
            assertEquals(listOf("A, B"), notes.shown)
            assertEquals(originalRecords, records, "Failed checks must preserve deduplication records")
            fixture.failingTable = null
            service.checkAndPrompt()
            assertEquals(listOf("toast-1"), notes.dismissed)
            assertEquals(listOf("A, B", "A 1 → 2"), notes.shown)
        } finally { fixture.close() }
    }
}
