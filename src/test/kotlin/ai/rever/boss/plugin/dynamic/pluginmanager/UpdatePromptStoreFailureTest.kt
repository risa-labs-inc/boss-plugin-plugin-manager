package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.McpToolArgs
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Real Postgrest responses pass through the API and production prompt service. */
class UpdatePromptStoreFailureTest {
    private class Fixture {
        val directory = Files.createTempDirectory("prompt-store-failure").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failingTable: String? = null
        var storeCount = 2
        var extraInstalled = emptyList<LoadedPluginInfo>()
        var omitVersionFor: String? = null
        private fun ids() = listOf("a", "b") + (3..storeCount).map { "p$it" }
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
                        "plugins_with_latest_version" -> ids().joinToString(",", "[", "]") {
                            """{"plugin_id":"$it","latest_version":"2"}"""
                        }
                        "plugins" -> ids().joinToString(",", "[", "]") {
                            """{"id":"uuid-$it","plugin_id":"$it","display_name":"$it"}"""
                        }
                        "plugin_versions" -> ids().filterNot { it == omitVersionFor }.joinToString(",", "[", "]") {
                            """{"id":"version-$it","plugin_id":"uuid-$it","version":"2"}"""
                        }
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
                "getLoadedPlugins" -> ids().map { LoadedPluginInfo(it, it.uppercase(), "1") } + extraInstalled
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

    @Test
    fun `MCP checks 36 store plugins plus sideload in three batched queries`() = runBlocking {
        val f = Fixture()
        try {
            f.storeCount = 36
            f.extraInstalled = listOf(LoadedPluginInfo("sideload", "Sideload", "1"))
            f.omitVersionFor = "b"
            val tool = PluginManagerMcpToolProvider("toolbox", f.api, f.api::currentInstalledPlugins)
                .tools().single { it.name == "plugin_check_updates" }
            val result = tool.handler.call(McpToolArgs(emptyMap()))
            assertFalse(result.isError)
            val rows = Json.parseToJsonElement(result.text).jsonObject["plugins"]!!.jsonArray
            assertEquals(37, rows.size)
            assertEquals(35, rows.count { it.jsonObject["status"]!!.jsonPrimitive.content == "checked" })
            assertEquals("lookup_failed", rows[1].jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals("not_in_store", rows.last().jsonObject["status"]!!.jsonPrimitive.content)
            assertTrue(rows.first().jsonObject["update_available"]!!.jsonPrimitive.boolean)
            assertEquals(listOf("plugins_with_latest_version", "plugins", "plugin_versions"), f.requests)
        } finally { f.close() }
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
