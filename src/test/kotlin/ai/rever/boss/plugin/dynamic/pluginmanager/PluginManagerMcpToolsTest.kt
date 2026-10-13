package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.dynamic.pluginmanager.api.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class PluginManagerMcpToolsTest {
    private class Fixture {
        var installed = listOf(PluginInfo("fixture", "Fixture", "1.0.0"))
        var versions = listOf(PluginVersionInfo("2.0.0", "1.0.0", compatibility = IpcCompat.Status.UNKNOWN))
        var latest = "2.0.0"
        var lookupFails = false
        var calls = mutableListOf<List<Any?>>()
        var result: InstallResult = InstallResult.Success(PluginInfo("fixture", "Fixture", "2.0.0"))
        // Suspend functions returning inline Result expose its unboxed representation to Java.
        private fun unbox(result: Result<*>): Any? =
            Result::class.java.getDeclaredMethod("unbox-impl").invoke(result)
        val api = Proxy.newProxyInstance(PluginManagerAPI::class.java.classLoader,
            arrayOf(PluginManagerAPI::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "getInstalledPlugins" -> installed
                "fetchPluginDetails" -> if (lookupFails) unbox(Result.failure<PluginStoreItem>(Exception("Store unavailable")))
                    else PluginStoreItem(pluginId = "fixture", latestVersion = latest)
                "fetchPluginVersions" -> versions
                "installStoreVersion" -> { calls += args.take(3); result }
                else -> error("Unexpected API call ${method.name}")
            }
        } as PluginManagerAPI
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
        assertEquals(listOf("fixture", "2.0.0", true), f.calls.single())
        f.installed = f.installed.map { it.copy(canUnload = false) }
        assertContains(f.call("plugin_update", "plugin_id" to "fixture", "version" to "2.0.0").text, "\"needs_restart\":true")
        f.installed = emptyList()
        val installed = f.call("plugin_install", "plugin_id" to "fixture", "version" to "2.0.0")
        assertContains(installed.text, "\"status\":\"installed\"")
        assertEquals(listOf("fixture", "2.0.0", false), f.calls.last())
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

    private suspend fun withProperty(key: String, value: String, block: suspend () -> Unit) {
        val previous = System.getProperty(key)
        try { System.setProperty(key, value); block() }
        finally { if (previous == null) System.clearProperty(key) else System.setProperty(key, previous) }
    }
}
