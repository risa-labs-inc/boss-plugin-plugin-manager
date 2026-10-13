package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.dynamic.pluginmanager.api.ExtractedManifest
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.IpcCompat
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginEvent
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginVersionInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UninstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginManagerMcpToolsTest {
    private class Fixture {
        var installed = listOf(PluginInfo("fixture", "Fixture", "1.0.0"))
        var versions = listOf(PluginVersionInfo("2.0.0", "1.0.0", compatibility = IpcCompat.Status.UNKNOWN))
        var latest = "2.0.0"
        var lookupFails = false
        var calls = mutableListOf<MutationCall>()
        var batchCalls = 0
        var detailCalls = 0
        var notInStore = emptySet<String>()
        var failingIds = emptySet<String>()
        var result: InstallResult = InstallResult.Success(PluginInfo("fixture", "Fixture", "2.0.0"))
        val api = object : UnusedApi() {
            override fun getInstalledPlugins() = installed
            override suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem> {
                detailCalls++
                return if (lookupFails) Result.failure(Exception("Store unavailable"))
                else Result.success(PluginStoreItem(pluginId = pluginId, latestVersion = latest))
            }
            override suspend fun fetchPluginVersions(pluginId: String) = Result.success(versions)
            override suspend fun fetchLatestStoreVersions(pluginIds: List<String>): Result<Map<String, Result<PluginVersionInfo>>> {
                batchCalls++
                if (lookupFails) return Result.failure(Exception("Store unavailable"))
                return Result.success(pluginIds.filterNot { it in notInStore }.associateWith {
                    if (it in failingIds) Result.failure(Exception("Version lookup failed"))
                    else Result.success(versions.first { it.version == latest })
                })
            }
            override suspend fun installStoreVersion(pluginId: String, version: String, update: Boolean, allowDowngrade: Boolean): InstallResult {
                calls += MutationCall(pluginId, version, update, allowDowngrade)
                return result
            }
        }
        val tools = PluginManagerMcpToolProvider("toolbox", api).tools().associateBy { it.name }
        suspend fun call(name: String, vararg args: Pair<String, Any?>) =
            tools.getValue(name).handler.call(McpToolArgs(mapOf(*args)))
    }

    @Test fun `check result includes latest and compatibility even when blocked`() = runBlocking {
        withProperty("boss.app.version", "9.4.22") {
            val f = Fixture()
            f.versions = f.versions.map { it.copy(minBossVersion = "9.4.23") }
            val result = f.call("plugin_check_updates")
            assertFalse(result.isError)
            val row = Json.parseToJsonElement(result.text).jsonObject["plugins"]!!.jsonArray.single().jsonObject
            assertEquals("1.0.0", row["installed_version"]!!.jsonPrimitive.content)
            assertEquals("2.0.0", row["latest_version"]!!.jsonPrimitive.content)
            assertTrue(row["update_available"]!!.jsonPrimitive.boolean)
            assertFalse(row["installable"]!!.jsonPrimitive.boolean)
            assertContains(row["blocked_reason"]!!.jsonPrimitive.content, "9.4.23")
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun `both mutations refuse boss and API floors without calling installer`() = runBlocking {
        withProperty("boss.app.version", "9.4.22") {
            withProperty("boss.api.version", "1.0.85") {
                for (update in listOf(true, false)) for (apiFloor in listOf(true, false)) {
                    val f = Fixture()
                    if (!update) f.installed = emptyList()
                    f.versions = f.versions.map { if (apiFloor) it.copy(minApiVersion = "1.0.86") else it.copy(minBossVersion = "9.4.23") }
                    val result = f.call(if (update) "plugin_update" else "plugin_install", "plugin_id" to "fixture")
                    assertTrue(result.isError)
                    assertContains(result.text, if (apiFloor) "1.0.86" else "9.4.23")
                    assertTrue(f.calls.isEmpty())
                }
            }
        }
    }

    @Test fun `mutations pin latest and explicit versions and report restart`() = runBlocking {
        val f = Fixture()
        val updated = f.call("plugin_update", "plugin_id" to "fixture")
        val row = Json.parseToJsonElement(updated.text).jsonObject
        assertFalse(updated.isError)
        assertEquals("updated", row["status"]!!.jsonPrimitive.content)
        assertEquals("1.0.0", row["from_version"]!!.jsonPrimitive.content)
        assertEquals("2.0.0", row["to_version"]!!.jsonPrimitive.content)
        assertFalse(row["needs_restart"]!!.jsonPrimitive.boolean)
        assertEquals(MutationCall("fixture", "2.0.0", true, false), f.calls.single())
        // The handler snapshot says unloadable; the installer reports staging under the lease.
        f.result = InstallResult.Success(PluginInfo("fixture", "Fixture", "2.0.0"), needsRestart = true)
        assertContains(f.call("plugin_update", "plugin_id" to "fixture", "version" to "2.0.0").text, "\"needs_restart\":true")
        f.installed = f.installed.map { it.copy(canUnload = false) }
        f.result = InstallResult.Success(PluginInfo("fixture", "Fixture", "2.0.0"), needsRestart = false)
        assertContains(f.call("plugin_update", "plugin_id" to "fixture").text, "\"needs_restart\":false")
        f.installed = emptyList()
        val installed = f.call("plugin_install", "plugin_id" to "fixture", "version" to "2.0.0")
        assertContains(installed.text, "\"status\":\"installed\"")
        assertEquals(MutationCall("fixture", "2.0.0", false, true), f.calls.last())
    }

    @Test fun `unknown ids invalid versions and store failures are errors`() = runBlocking {
        val f = Fixture()
        for (tool in listOf("plugin_update", "plugin_check_updates")) {
            assertTrue(f.call(tool, "plugin_id" to "unknown").isError)
        }
        assertTrue(f.call("plugin_update", "plugin_id" to "fixture", "version" to "missing").isError)
        f.lookupFails = true
        assertTrue(f.call("plugin_install", "plugin_id" to "unknown").isError)
        assertTrue(f.call("plugin_check_updates").isError)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `already latest never calls installer and errors preserve reasons`() = runBlocking {
        val f = Fixture()
        f.installed = f.installed.map { it.copy(version = "2.0.0") }
        assertContains(f.call("plugin_update", "plugin_id" to "fixture").text, "already_latest")
        assertTrue(f.calls.isEmpty())
        f.installed = emptyList()
        for (failure in listOf(InstallResult.LoadFailed("Signature verification failed"), InstallResult.DownloadFailed("busy"))) {
            f.result = failure
            val result = f.call("plugin_install", "plugin_id" to "fixture")
            assertTrue(result.isError)
            assertContains(result.text, if (failure is InstallResult.LoadFailed) "Signature verification failed" else "busy")
        }
    }

    @Test fun `all mutating tools are admin gated`() {
        val tools = Fixture().tools
        for (name in listOf("plugin_enable", "plugin_disable", "plugin_update", "plugin_install")) {
            assertTrue(tools.getValue(name).requiresAdmin, name)
            assertFalse(tools.getValue(name).readOnly, name)
        }
        assertTrue(tools.getValue("plugin_check_updates").readOnly)
        assertFalse(tools.getValue("plugin_check_updates").requiresAdmin)
    }

    @Test fun `protected MCP plugins cannot update even when unloadable`() = runBlocking {
        for (id in listOf("ai.rever.boss.plugin.dynamic.terminaltab", TOOLBOX_PLUGIN_ID)) {
            val f = Fixture()
            f.installed = listOf(PluginInfo(id, "Protected", "1.0.0", canUnload = true))
            for (version in listOf(null, "2.0.0")) {
                val args = mutableListOf<Pair<String, Any?>>("plugin_id" to id)
                if (version != null) args += "version" to version
                val result = f.call("plugin_update", *args.toTypedArray())
                assertTrue(result.isError)
                assertContains(result.text, "Refusing to update MCP channel")
            }
            assertTrue(f.calls.isEmpty())
            assertEquals(0, f.detailCalls)
        }
    }

    @Test fun `mixed checks preserve checked rows without flagging the call as an error`() = runBlocking {
        for (missing in listOf(true, false)) {
            val f = Fixture()
            f.installed += PluginInfo("sideload", "Sideload", "1.0.0")
            if (missing) f.notInStore = setOf("sideload") else f.failingIds = setOf("sideload")
            val result = f.call("plugin_check_updates")
            assertFalse(result.isError)
            val rows = Json.parseToJsonElement(result.text).jsonObject["plugins"]!!.jsonArray
            assertEquals("checked", rows[0].jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals("2.0.0", rows[0].jsonObject["latest_version"]!!.jsonPrimitive.content)
            assertEquals(if (missing) "not_in_store" else "lookup_failed", rows[1].jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(1, f.batchCalls)
            assertEquals(0, f.detailCalls)
        }
        val f = Fixture()
        f.notInStore = setOf("fixture")
        assertTrue(f.call("plugin_check_updates").isError)
    }

    @Test fun `explicit downgrade passes allowDowngrade and same version reports already installed`() = runBlocking {
        val f = Fixture()
        f.installed = f.installed.map { it.copy(version = "3.0.0") }
        assertFalse(f.call("plugin_update", "plugin_id" to "fixture", "version" to "2.0.0").isError)
        assertEquals(MutationCall("fixture", "2.0.0", true, true), f.calls.single())
        f.installed = f.installed.map { it.copy(version = "2.0.0") }
        assertContains(f.call("plugin_update", "plugin_id" to "fixture", "version" to "2.0.0").text, "already_installed")
        // Also exercise the no-op discovered by the installer after acquiring the lease.
        f.installed = f.installed.map { it.copy(version = "1.0.0") }
        f.result = InstallResult.AlreadyInstalled("2.0.0")
        assertContains(f.call("plugin_update", "plugin_id" to "fixture", "version" to "2.0.0").text, "already_installed")
    }

    @Test fun `default store mutation fails loudly instead of dropping intent flags`() = runBlocking {
        val api = object : UnusedApi() {}
        for (update in listOf(true, false)) for (downgrade in listOf(true, false)) {
            val result = assertIs<InstallResult.DownloadFailed>(api.installStoreVersion("fixture", "1.0.0", update, downgrade))
            assertContains(result.error, "unsupported")
        }
    }

    private fun status(result: ai.rever.boss.plugin.api.McpToolResult) =
        Json.parseToJsonElement(result.text).jsonObject["status"]!!.jsonPrimitive.content

    @Test fun `errors distinguish refusals, installer failures and a busy lease`() = runBlocking {
        val f = Fixture()
        f.installed = emptyList()
        val cases = listOf(
            InstallResult.DownloadFailed(UPDATE_INSTALL_BUSY) to "busy",
            InstallResult.DownloadFailed("Connection reset") to "failed",
            // A manifest floor or in-lease intent refusal from the installer is deterministic.
            policyRefusal("Needs BOSS API 1.0.86 (host 1.0.85)") to "refused",
            InstallResult.LoadFailed("Signature verification failed") to "failed",
            InstallResult.VersionConflict("1.0.90", "1.0.85") to "failed",
        )
        for ((installResult, expected) in cases) {
            f.result = installResult
            val result = f.call("plugin_install", "plugin_id" to "fixture")
            assertTrue(result.isError)
            assertEquals(expected, status(result), installResult.toString())
        }
        f.installed = listOf(PluginInfo("fixture", "Fixture", "1.0.0"))
        assertEquals("refused", status(f.call("plugin_install", "plugin_id" to "fixture")))
        assertEquals("refused", status(f.call("plugin_update", "plugin_id" to "fixture", "version" to "9.9.9")))
        f.lookupFails = true
        assertEquals("failed", status(f.call("plugin_update", "plugin_id" to "fixture")))
    }

    @Test fun `host-blocked latest is refused with a loadable alternative that installs explicitly`() = runBlocking {
        withProperty("boss.app.version", "9.4.22") {
            for (update in listOf(false, true)) {
                val f = Fixture()
                f.installed = if (update) listOf(PluginInfo("fixture", "Fixture", "1.0.0")) else emptyList()
                f.versions = listOf(
                    PluginVersionInfo("2.0.0", "1.0.0", compatibility = IpcCompat.Status.UNKNOWN, minBossVersion = "9.4.23"),
                    PluginVersionInfo("1.5.0", "1.0.0", compatibility = IpcCompat.Status.UNKNOWN, minBossVersion = "9.4.0"),
                    PluginVersionInfo("1.0.0", "1.0.0", compatibility = IpcCompat.Status.UNKNOWN),
                )
                val tool = if (update) "plugin_update" else "plugin_install"
                val refused = f.call(tool, "plugin_id" to "fixture")
                assertTrue(refused.isError)
                assertEquals("refused", status(refused))
                assertContains(refused.text, "9.4.23")
                assertContains(refused.text, "newest version loadable here is 1.5.0")
                assertTrue(f.calls.isEmpty())
                f.result = InstallResult.Success(PluginInfo("fixture", "Fixture", "1.5.0"))
                val explicit = f.call(tool, "plugin_id" to "fixture", "version" to "1.5.0")
                assertFalse(explicit.isError)
                assertEquals(if (update) "updated" else "installed", status(explicit))
                assertEquals(MutationCall("fixture", "1.5.0", update, true), f.calls.single())
            }
        }
    }

    @Test fun `blank plugin id checks every installed plugin`() = runBlocking {
        val f = Fixture()
        f.installed += PluginInfo("other", "Other", "2.0.0")
        val result = f.call("plugin_check_updates", "plugin_id" to "  ")
        assertFalse(result.isError)
        assertEquals(2, Json.parseToJsonElement(result.text).jsonObject["plugins"]!!.jsonArray.size)
    }

    @Test fun `compiled MCP provider registers admin tools against the actual API 1_0_73 factory`() {
        val parent = javaClass.classLoader
        val baseline = mutableMapOf<String, ByteArray>()
        ZipInputStream(checkNotNull(parent.getResourceAsStream("compat/mcp-tool-definition-1.0.73.zip"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                baseline[entry.name.removeSuffix(".class").replace('/', '.')] = zip.readBytes()
            }
        }
        val providerName = PluginManagerMcpToolProvider::class.java.name
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                if (name !in baseline && name != providerName && !name.startsWith(providerName + "$")) {
                    return@synchronized super.loadClass(name, resolve)
                }
                val loaded = findLoadedClass(name) ?: run {
                    val bytes = baseline[name] ?: checkNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class"))
                        .use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                loaded
            }
        }
        val f = Fixture()
        val snapshot: () -> List<PluginInfo> = { f.installed }
        val type = loader.loadClass(providerName)
        val provider = type.declaredConstructors.single { it.parameterCount == 3 }
            .newInstance("toolbox", f.api, snapshot)
        val tools = type.getMethod("tools").invoke(provider) as List<*>
        assertEquals(6, tools.size)
        val adminNames = tools.filter { tool -> tool!!.javaClass.getMethod("getRequiresAdmin").invoke(tool) == true }
            .map { tool -> tool!!.javaClass.getMethod("getName").invoke(tool) }.toSet()
        assertEquals(setOf("plugin_enable", "plugin_disable", "plugin_update", "plugin_install"), adminNames)
    }

    @Test fun `legacy Success constructor and component retain their JVM descriptors`() {
        val plugin = PluginInfo("fixture", "Fixture", "1.0.0")
        val result = InstallResult.Success::class.java.getConstructor(PluginInfo::class.java).newInstance(plugin)
        assertFalse(result.needsRestart)
        assertEquals(plugin, InstallResult.Success::class.java.getMethod("component1").invoke(result))
    }

    @Test fun `policy marks do not classify unrelated equal failures as refused`() {
        val refused = policyRefusal("same text")
        val failed = InstallResult.DownloadFailed("same text")
        assertEquals(refused, failed)
        assertTrue(refused.wasRefused())
        assertFalse(failed.wasRefused())
    }

    private data class MutationCall(val id: String, val version: String, val update: Boolean, val allowDowngrade: Boolean)

    // These tests mutate process-wide host properties. Keep test execution serial within a JVM;
    // restore every property in finally before another compatibility test can run.
    private suspend fun withProperty(key: String, value: String, block: suspend () -> Unit) {
        val previous = System.getProperty(key)
        try { System.setProperty(key, value); block() }
        finally { if (previous == null) System.clearProperty(key) else System.setProperty(key, previous) }
    }

    /** Fail on any unexpected API route; no reflection or Kotlin ABI assumptions. */
    private open class UnusedApi : PluginManagerAPI {
        override fun getInstalledPlugins(): List<PluginInfo> = notUsed()
        override fun isPluginInstalled(pluginId: String): Boolean = notUsed()
        override fun getInstalledPlugin(pluginId: String): PluginInfo? = notUsed()
        override fun observeInstalledPlugins(): StateFlow<List<PluginInfo>> = notUsed()
        override suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem> = notUsed()
        override suspend fun installPlugin(pluginId: String): InstallResult = notUsed()
        override suspend fun fetchStorePlugins(query: String?, category: String?) = notUsed()
        override suspend fun fetchPluginVersions(pluginId: String): Result<List<PluginVersionInfo>> = notUsed()
        override suspend fun installVersion(pluginId: String, version: String): InstallResult = notUsed()
        override suspend fun checkForUpdates(): Map<String, String> = notUsed()
        override suspend fun checkForCompatibleUpdates(): List<UpdateInfo> = notUsed()
        override suspend fun installFromGitHub(githubUrl: String): InstallResult = notUsed()
        override suspend fun installFromFile(jarPath: String): InstallResult = notUsed()
        override suspend fun uninstallPlugin(pluginId: String): UninstallResult = notUsed()
        override suspend fun updatePlugin(pluginId: String): InstallResult = notUsed()
        override suspend fun enablePlugin(pluginId: String): Boolean = notUsed()
        override suspend fun disablePlugin(pluginId: String): Boolean = notUsed()
        override fun observeEvents(): Flow<PluginEvent> = notUsed()
        override suspend fun deleteFromStore(pluginId: String): Result<Unit> = notUsed()
        override suspend fun fetchFromGitHubForPublish(
            url: String,
            onProgress: (Float) -> Unit,
            onStatus: (String) -> Unit,
            onSuccess: (String, ExtractedManifest) -> Unit,
            onError: (String) -> Unit,
        ) = notUsed()

        override suspend fun browseForPluginJar(onResult: (String?) -> Unit) = notUsed()
        override suspend fun extractManifest(jarPath: String, onResult: (ExtractedManifest?) -> Unit) = notUsed()
        override suspend fun publishPlugin(
            jarPath: String,
            pluginId: String,
            displayName: String,
            version: String,
            homepageUrl: String,
            authorName: String,
            description: String?,
            changelog: String?,
            tags: List<String>,
            iconUrl: String?,
            pluginType: String,
            apiVersion: String,
            minBossVersion: String,
            orgId: String?,
            onProgress: (Float) -> Unit,
            onSuccess: (String) -> Unit,
            onError: (String) -> Unit,
        ) = notUsed()

        private fun notUsed(): Nothing = error("not expected in this test")
    }
}
