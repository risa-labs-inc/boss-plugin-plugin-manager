package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UninstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.PluginManagerAPIImpl
import com.risaboss.toolbox.downloadcenter.TransferReporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Exercise real API routes against isolated disk/delegate state and in-memory HTTP responses. */
class PluginInstallerLeaseApiTest {
    private companion object { const val ID = "test.plugin" }

    private class MemoryConnection(bytes: ByteArray, private val status: Int = 200) :
        HttpURLConnection(URL("https://fixture.invalid/response")) {
        private val payload = bytes
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = ByteArrayInputStream(payload)
        override fun getErrorStream() = ByteArrayInputStream(payload)
        override fun getContentLengthLong(): Long = payload.size.toLong()
    }

    private class Reporter : TransferReporter {
        override val busyIds = MutableStateFlow<Set<String>>(emptySet())
        var begins = 0
        override fun begin(key: String, title: String, isUpdate: Boolean, onCancel: () -> Unit): Boolean {
            begins++
            busyIds.value += key
            return true
        }
        override fun progress(key: String, fraction: Float) = Unit
        override fun downloading(key: String) = Unit
        override fun installing(key: String) = Unit
        override fun end(key: String) { busyIds.value -= key }
    }

    private class Delegate(val directory: File) : PluginLoaderDelegate {
        var loaded = emptyList<LoadedPluginInfo>()
        var reads = 0
        var unloads = 0
        var loads = 0
        var onRead: (() -> Unit)? = null
        var allowUnload = true
        override fun getLoadedPlugins(): List<LoadedPluginInfo> {
            reads++
            onRead?.invoke()
            return loaded
        }
        override suspend fun loadPlugin(jarPath: String): LoadedPluginInfo {
            loads++
            return LoadedPluginInfo(ID, "Fixture", "2.0.0", jarPath = jarPath).also { loaded = listOf(it) }
        }
        override suspend fun unloadPlugin(pluginId: String): Boolean {
            unloads++
            if (allowUnload) loaded = loaded.filterNot { it.pluginId == pluginId }
            return allowUnload
        }
        override suspend fun reloadPlugin(pluginId: String): LoadedPluginInfo? = null
        override fun isPluginLoaded(pluginId: String) = loaded.any { it.pluginId == pluginId }
        override fun getPluginsDirectory() = directory.path
        override fun getBundledPluginsDirectory() = directory.path
        override fun isCurrentUserAdmin() = false
        override suspend fun enablePlugin(pluginId: String) = true
        override suspend fun disablePlugin(pluginId: String) = true
        override fun getAccessToken() = ""
        override fun getRunningInstanceCount(pluginId: String) = 0
        override fun getInaccessiblePlugins(): List<InaccessiblePluginInfo> = emptyList()
    }

    private class Fixture(val directory: File) {
        val plugins = File(directory, "plugins").apply { mkdirs() }
        val original = File(plugins, "installed.jar").apply { writeText("irreplaceable installed bytes") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delegate = Delegate(plugins).apply { loaded = listOf(info("1.0.0", original)) }
        val reporter = Reporter()
        val requests = mutableListOf<String>()
        var connection: (String) -> HttpURLConnection = { error("Unexpected installer network request: $it") }
        val api = PluginManagerAPIImpl(scope, delegate, reporter) { url ->
            requests += url
            connection(url)
        }

        fun info(version: String, jar: File = original, locked: Boolean = false, url: String = "") =
            LoadedPluginInfo(ID, "Fixture", version, jarPath = jar.path,
                isSystemPlugin = locked, canUnload = !locked, url = url)

        fun assertNoMutation() {
            assertEquals("irreplaceable installed bytes", original.readText())
            assertEquals(0, delegate.unloads)
            assertEquals(0, delegate.loads)
        }

        fun github(bytes: ByteArray) {
            connection = { url ->
                when {
                    url.endsWith("/releases/latest") -> MemoryConnection(
                        """{"browser_download_url":"https://fixture.invalid/incoming.jar"}""".toByteArray())
                    url == "https://fixture.invalid/incoming.jar" -> MemoryConnection(bytes)
                    else -> error("Unexpected request $url")
                }
            }
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("toolbox-lease-api").toFile()
        val f = Fixture(directory)
        try { f.block() } finally { f.scope.cancel(); directory.deleteRecursively() }
    }

    private fun jar(version: String = "2.0.0", id: String = ID, payload: String? = null): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/boss-plugin/plugin.json"))
                zip.write("""{"pluginId":"$id","version":"$version","displayName":"Fixture"}""".toByteArray())
                zip.closeEntry()
                if (payload != null) {
                    zip.putNextEntry(ZipEntry("implementation.txt"))
                    zip.write(payload.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    @Test
    fun `busy leases stop every known identity route before reads downloads or mutation`() = runBlocking {
        fixture {
            val incoming = File(directory, "incoming.jar").apply { writeBytes(jar()) }
            val reads = delegate.reads
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().use {
                assertTrue(api.installPlugin(ID).wasBusy())
                assertTrue(api.installVersion(ID, "2.0.0").wasBusy())
                assertTrue(api.updatePlugin(ID).wasBusy())
                assertTrue(api.installFromFile(incoming.path).wasBusy())
                assertTrue(api.uninstallPlugin(ID).wasBusy())
            }
            assertEquals(reads, delegate.reads)
            assertEquals(emptyList(), requests)
            assertEquals(0, reporter.begins)
            assertFalse(File(plugins, incoming.name).exists())
            assertNoMutation()
        }
    }

    @Test
    fun `github discovers identity first then busy prevents unload promotion and orphan staging`() = runBlocking {
        fixture {
            github(jar())
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().use {
                assertTrue(api.installFromGitHub("https://github.com/fixture/plugin").wasBusy())
            }
            assertEquals(2, requests.size)
            assertFalse(File(plugins, "incoming.jar").exists())
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") })
            assertTrue(reporter.busyIds.value.isEmpty())
            assertNoMutation()
        }
    }

    @Test
    fun `ordinary install uses refreshed state inside the lease before any network`() = runBlocking {
        fixture {
            original.writeBytes(jar("2.0.0"))
            val before = original.readBytes()
            delegate.loaded = listOf(info("2.0.0"))
            delegate.onRead = { assertTrue(PluginUpdateLease.acquire(plugins, ID).isFailure) }
            assertEquals(InstallResult.AlreadyInstalled("2.0.0"), api.installPlugin(ID))
            assertEquals("2.0.0", api.getInstalledPlugin(ID)?.version)
            assertEquals(emptyList(), requests)
            assertContentEquals(before, original.readBytes())
            assertEquals(0, delegate.unloads)
            assertEquals(0, delegate.loads)
        }
    }

    @Test
    fun `an explicit current version repairs its jar bytes and reloads instead of returning already installed`() = runBlocking {
        fixture {
            val damaged = jar("2.0.0", payload = "damaged implementation")
            val requested = jar("2.0.0", payload = "replacement implementation")
            val current = File(plugins, "test_plugin_2.0.0.jar").apply { writeBytes(damaged) }
            delegate.loaded = listOf(info("2.0.0", current))
            connection = { url ->
                when {
                    url.endsWith("/download/2.0.0") -> MemoryConnection(
                        """{"downloadUrl":"https://fixture.invalid/repair.jar","version":"2.0.0"}""".toByteArray())
                    url == "https://fixture.invalid/repair.jar" -> MemoryConnection(requested)
                    else -> error("Unexpected request $url")
                }
            }
            val result = api.installVersion(ID, "2.0.0")
            assertIs<InstallResult.Success>(result)
            assertEquals(current.path, result.plugin.jarPath)
            assertContentEquals(requested, current.readBytes())
            assertEquals(2, requests.size)
            assertEquals(1, delegate.unloads)
            assertEquals(1, delegate.loads)
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") })
        }
    }

    @Test
    fun `an explicit version restores a locked plugins pending disk update even when that version is still loaded`() = runBlocking {
        fixture {
            original.writeBytes(jar("2.0.0"))
            val requested = jar("1.0.0")
            delegate.loaded = listOf(info("1.0.0", locked = true))
            connection = { url ->
                when {
                    url.endsWith("/download/1.0.0") -> MemoryConnection(
                        """{"downloadUrl":"https://fixture.invalid/requested.jar","version":"1.0.0"}""".toByteArray())
                    url == "https://fixture.invalid/requested.jar" -> MemoryConnection(requested)
                    else -> error("Unexpected request $url")
                }
            }
            val result = api.installVersion(ID, "1.0.0")
            assertIs<InstallResult.Success>(result)
            assertEquals("1.0.0", result.plugin.version)
            assertContentEquals(requested, original.readBytes())
            assertEquals(2, requests.size)
            assertEquals(0, delegate.unloads)
            assertEquals(0, delegate.loads)
        }
    }

    @Test
    fun `uninstall refreshes the current jar path while leased instead of deleting a stale path`() = runBlocking {
        fixture {
            val current = File(plugins, "current.jar").apply { writeText("current installed bytes") }
            delegate.loaded = listOf(info("2.0.0", current))
            delegate.onRead = { assertTrue(PluginUpdateLease.acquire(plugins, ID).isFailure) }
            assertEquals(UninstallResult.Success, api.uninstallPlugin(ID))
            assertFalse(current.exists())
            assertTrue(original.exists(), "the cached old path was incorrectly deleted")
            assertEquals(1, delegate.unloads)
            assertTrue(api.getInstalledPlugins().isEmpty())
        }
    }

    @Test
    fun `an update removed by another installer stops after the leased refresh`() = runBlocking {
        fixture {
            delegate.loaded = emptyList()
            assertEquals(InstallResult.DownloadFailed("Plugin not installed: $ID"), api.updatePlugin(ID))
            assertEquals(emptyList(), requests)
            assertNoMutation()
        }
    }

    @Test
    fun `current store latest is terminal before jar download even with a github fallback`() = runBlocking {
        for (locked in listOf(false, true)) fixture {
            delegate.loaded = listOf(info("2.0.0", locked = locked, url = "https://github.com/fixture/plugin"))
            connection = { url ->
                assertTrue(url.endsWith("/download"))
                MemoryConnection("""{"downloadUrl":"https://fixture.invalid/new.jar","version":"2.0.0"}""".toByteArray())
            }
            assertEquals(InstallResult.AlreadyInstalled("2.0.0"), api.updatePlugin(ID))
            assertEquals(1, requests.size, "already-current must not fall through to GitHub or fetch bytes")
            assertNoMutation()
        }
    }

    @Test
    fun `github update uses current installed version under its held identity lease`() = runBlocking {
        fixture {
            github(jar("2.0.0"))
            val githubConnection = connection
            connection = { url -> if (url.endsWith("/download")) MemoryConnection(byteArrayOf(), 503) else githubConnection(url) }
            delegate.loaded = listOf(info("2.0.0", url = "https://github.com/fixture/plugin"))
            delegate.onRead = { assertTrue(PluginUpdateLease.acquire(plugins, ID).isFailure) }
            assertEquals(InstallResult.AlreadyInstalled("2.0.0"), api.updatePlugin(ID))
            assertFalse(File(plugins, "incoming.jar").exists())
            assertNoMutation()
        }
    }

    @Test
    fun `explicit github install permits a same version repair under its discovered lease`() = runBlocking {
        fixture {
            val incoming = jar("2.0.0")
            github(incoming)
            delegate.loaded = listOf(info("2.0.0"))
            delegate.onRead = { assertTrue(PluginUpdateLease.acquire(plugins, ID).isFailure) }
            assertIs<InstallResult.Success>(api.installFromGitHub("https://github.com/fixture/plugin"))
            assertEquals(1, delegate.unloads)
            assertEquals(1, delegate.loads)
            assertContentEquals(incoming, File(plugins, "incoming.jar").readBytes())
        }
    }

    @Test
    fun `github fallback reuses the held update lease and validates its expected identity`() = runBlocking {
        for (matching in listOf(true, false)) fixture {
            // Older APIs report the manifest homepage as fallback provenance; the store remains first.
            delegate.loaded = listOf(info("1.0.0", url = "https://github.com/fixture/plugin"))
            github(jar(id = if (matching) ID else "other.plugin"))
            val githubConnection = connection
            connection = { url -> if (url.endsWith("/download")) MemoryConnection(byteArrayOf(), 503) else githubConnection(url) }
            val result = api.updatePlugin(ID)
            if (matching) {
                assertIs<InstallResult.Success>(result, "a nested lease would incorrectly report busy")
                assertEquals(1, delegate.unloads)
                assertEquals(1, delegate.loads)
            } else {
                assertIs<InstallResult.LoadFailed>(result)
                assertNoMutation()
            }
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") })
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().close()
        }
    }

    @Test
    fun `local replacement refuses failed unload and preserves destination and staging cleanup`() = runBlocking {
        fixture {
            val incoming = File(directory, original.name).apply { writeBytes(jar()) }
            delegate.allowUnload = false
            assertIs<InstallResult.LoadFailed>(api.installFromFile(incoming.path))
            assertEquals("irreplaceable installed bytes", original.readText())
            assertEquals(0, delegate.loads)
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") })
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().close()
        }
    }

    @Test
    fun `coroutine cancellation and fatal installer failures propagate and release the disk lease`() = runBlocking {
        for (fatal in listOf(false, true)) fixture {
            val failure = if (fatal) AssertionError("fatal installer failure") else CancellationException("canceled")
            connection = { throw failure }
            if (fatal) assertFailsWith<AssertionError> { api.updatePlugin(ID) }
            else assertFailsWith<CancellationException> { api.updatePlugin(ID) }
            assertNoMutation()
            assertTrue(reporter.busyIds.value.isEmpty())
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().close()
        }
    }

    @Test
    fun `flagged download cancellation remains neutral and never falls through to github`() = runBlocking {
        fixture {
            delegate.loaded = listOf(info("1.0.0", url = "https://github.com/fixture/plugin"))
            connection = { throw DownloadCancelledException() }
            assertTrue(api.updatePlugin(ID).wasCancelled())
            assertEquals(1, requests.size)
            assertNoMutation()
            assertTrue(reporter.busyIds.value.isEmpty())
            PluginUpdateLease.acquire(plugins, ID).getOrThrow().close()
        }
    }


    @Test
    fun `foreign plugin filename collisions never mutate the destination under the wrong lease`() = runBlocking {
        for (route in listOf("local", "github", "store")) fixture {
            val foreignId = "other.plugin"
            val name = if (route == "store") "test_plugin_2.0.0.jar" else "incoming.jar"
            val destination = File(plugins, name).apply { writeBytes(jar("1.0.0", foreignId)) }
            val before = destination.readBytes()
            delegate.loaded = listOf(LoadedPluginInfo(foreignId, "Foreign fixture", "1.0.0", jarPath = destination.path))
            github(jar())
            if (route == "store") connection = { url ->
                when {
                    url.endsWith("/download/2.0.0") -> MemoryConnection(
                        """{"downloadUrl":"https://fixture.invalid/incoming.jar","version":"2.0.0"}""".toByteArray())
                    url == "https://fixture.invalid/incoming.jar" -> MemoryConnection(jar())
                    else -> error("Unexpected request $url")
                }
            }
            PluginUpdateLease.acquire(plugins, foreignId).getOrThrow().use {
                val result = when (route) {
                    "local" -> {
                        val incoming = File(directory, name).apply { writeBytes(jar()) }
                        api.installFromFile(incoming.path)
                    }
                    "github" -> api.installFromGitHub("https://github.com/fixture/plugin")
                    else -> api.installVersion(ID, "2.0.0")
                }
                assertIs<InstallResult.LoadFailed>(result)
                assertContentEquals(before, destination.readBytes())
                assertEquals(0, delegate.unloads)
                assertEquals(0, delegate.loads)
            }
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") })
        }
    }


    @Test
    fun `a missing or blank offered version still downloads and applies a regular plugin update`() = runBlocking {
        verifyUnknownOfferedVersion(locked = false)
    }

    @Test
    fun `a missing or blank offered version stages a locked update and reports its verified manifest version`() = runBlocking {
        verifyUnknownOfferedVersion(locked = true)
    }

    private suspend fun verifyUnknownOfferedVersion(locked: Boolean) {
        for (offeredVersion in listOf(null, "", " ")) fixture {
            original.writeBytes(jar("1.0.0", payload = "old implementation"))
            val requested = jar("2.0.0", payload = "replacement implementation")
            delegate.loaded = listOf(info("1.0.0", locked = locked))
            connection = { url ->
                when {
                    url.endsWith("/download") -> {
                        val versionField = offeredVersion?.let { ",\"version\":\"$it\"" }.orEmpty()
                        MemoryConnection("""{"downloadUrl":"https://fixture.invalid/update.jar"$versionField}""".toByteArray())
                    }
                    url == "https://fixture.invalid/update.jar" -> MemoryConnection(requested)
                    else -> error("Unexpected request $url")
                }
            }
            val result = api.updatePlugin(ID)
            assertIs<InstallResult.Success>(result, "unknown offered version must not become already-current")
            assertEquals("2.0.0", result.plugin.version)
            assertContentEquals(requested, File(result.plugin.jarPath).readBytes())
            assertEquals(2, requests.size)
            assertEquals(if (locked) 0 else 1, delegate.unloads)
            assertEquals(if (locked) 0 else 1, delegate.loads)
            if (locked) assertEquals("1.0.0", delegate.loaded.single().version)
            assertFalse(plugins.listFiles()!!.any { it.name.endsWith(".part") || it.name.endsWith(".update") })
        }
    }

}
