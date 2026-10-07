package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.assertTrue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatePromptPolicyRegressionTest {
    private class Notes : NotificationProvider {
        val shown = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val types = mutableListOf<NotificationType>()
        var action: (() -> Unit)? = null
        override fun showToast(message: String, type: NotificationType, duration: NotificationDuration,
            title: String?, actionLabel: String?, onAction: (() -> Unit)?): String {
            shown += message
            types += type
            if (onAction != null) action = onAction
            return "toast-${shown.size}"
        }
        override fun dismiss(notificationId: String) { dismissed += notificationId }
        override fun dismissAll() {}
    }

    @Test
    fun `failed check preserves a mixed prompt and successful check reoffers only opted out plugins`(): Unit = runTest {
        val updates = listOf(UpdateInfo("a", "A", "1", "2"), UpdateInfo("b", "B", "1", "2"))
        var failed = false
        var policy = HostAutomaticUpdatePolicy(false, emptySet())
        val api = Proxy.newProxyInstance(PluginManagerAPI::class.java.classLoader,
            arrayOf(PluginManagerAPI::class.java)) { _, method, _ ->
            when (method.name) {
                "checkForCompatibleUpdates" -> {
                    if (failed) error("Store unavailable")
                    updates
                }
                "getInstalledPlugins" -> emptyList<PluginInfo>()
                else -> error("Unexpected call: ${method.name}")
            }
        } as PluginManagerAPI
        val notes = Notes()
        val service = UpdatePromptService(this, api, null, notes, null, { policy })
        service.checkAndPrompt()
        assertEquals(1, notes.shown.size)
        policy = HostAutomaticUpdatePolicy(true, setOf("a"))
        failed = true
        service.checkAndPrompt()
        assertEquals(emptyList(), notes.dismissed)
        failed = false
        service.checkAndPrompt()
        assertEquals(listOf("toast-1"), notes.dismissed)
        assertEquals(2, notes.shown.size)
        assertEquals("A 1 → 2", notes.shown.last())
    }

    @Test
    fun `stale toast action does not call the installer after automatic mode is enabled`(): Unit = runTest {
        var policy = HostAutomaticUpdatePolicy(false, emptySet())
        var installs = 0
        val api = Proxy.newProxyInstance(PluginManagerAPI::class.java.classLoader,
            arrayOf(PluginManagerAPI::class.java)) { _, method, _ ->
            when (method.name) {
                "checkForCompatibleUpdates" -> listOf(UpdateInfo("a", "A", "1", "2"))
                "getInstalledPlugins" -> emptyList<PluginInfo>()
                "updatePlugin" -> { installs++; error("Must not install") }
                else -> error("Unexpected call: ${method.name}")
            }
        } as PluginManagerAPI
        val notes = Notes()
        val service = UpdatePromptService(this, api, null, notes, null, { policy })
        service.checkAndPrompt()
        policy = HostAutomaticUpdatePolicy(true, emptySet())
        notes.action!!.invoke()
        testScheduler.runCurrent()
        assertEquals(0, installs)
        assertEquals(listOf("toast-1"), notes.dismissed)
        assertEquals("Now managed by automatic updates: A", notes.shown.last())
        assertEquals(NotificationType.INFO, notes.types.last())
        policy = HostAutomaticUpdatePolicy(false, emptySet())
        service.checkAndPrompt()
        assertEquals("A 1 → 2", notes.shown.last(), "Turning automatic mode off must re-offer a skipped version")
        assertEquals(3, notes.shown.size)
    }

    // Proxy stubs return immediately; a suspending stub must handle the continuation explicitly.
    private fun api(updates: List<UpdateInfo>, install: () -> InstallResult): PluginManagerAPI =
        Proxy.newProxyInstance(PluginManagerAPI::class.java.classLoader,
            arrayOf(PluginManagerAPI::class.java)) { _, method, _ ->
            when (method.name) {
                "checkForCompatibleUpdates" -> updates
                "getInstalledPlugins" -> emptyList<PluginInfo>()
                "updatePlugin" -> install()
                else -> error("Unexpected call: ${method.name}")
            }
        } as PluginManagerAPI

    @Test
    fun `busy installer is neutral and preserves prompt deduplication`(): Unit = runTest {
        val notes = Notes()
        var installs = 0
        val service = UpdatePromptService(this,
            api(listOf(UpdateInfo("a", "A", "1", "2"))) {
                installs++
                InstallResult.DownloadFailed(UPDATE_INSTALL_BUSY)
            }, null, notes, null, { HostAutomaticUpdatePolicy(false, emptySet()) })
        service.checkAndPrompt()
        notes.action!!.invoke()
        testScheduler.runCurrent()
        assertEquals(1, installs)
        assertEquals("Already being installed or updated: A", notes.shown.last())
        assertEquals(NotificationType.INFO, notes.types.last())
        service.checkAndPrompt()
        assertEquals(2, notes.shown.size, "A busy installer must not clear the existing version record")
    }

    @Test
    fun `already updated by another installer is not reported as failure`(): Unit = runTest {
        val notes = Notes()
        val service = UpdatePromptService(this,
            api(listOf(UpdateInfo("a", "A", "1", "2"))) { InstallResult.AlreadyInstalled("2") },
            null, notes, null, { HostAutomaticUpdatePolicy(false, emptySet()) })
        service.checkAndPrompt()
        notes.action!!.invoke()
        testScheduler.runCurrent()
        assertEquals("Already up to date: A", notes.shown.last())
        assertEquals(NotificationType.INFO, notes.types.last())
        service.checkAndPrompt()
        assertEquals(2, notes.shown.size)
    }

    @Test
    fun `automatic mode without opt outs emits no manual prompt`(): Unit = runTest {
        val notes = Notes()
        val service = UpdatePromptService(this,
            api(listOf(UpdateInfo("a", "A", "1", "2"))) { error("Must not install") },
            null, notes, null, { HostAutomaticUpdatePolicy(true, emptySet()) })
        service.checkAndPrompt()
        assertTrue(notes.shown.isEmpty())
    }

    @Test
    fun `concurrent successful checks replace mixed offer only once`(): Unit = runTest {
        val notes = Notes()
        val unusedStorage = Proxy.newProxyInstance(PluginStorageProvider::class.java.classLoader,
            arrayOf(PluginStorageProvider::class.java)) { _, method, _ ->
            error("Unexpected storage call: ${method.name}")
        } as PluginStorageProvider
        val storage = object : PluginStorageProvider by unusedStorage {
            var json: String? = null
            override suspend fun getJson(key: String): String? {
                val snapshot = json
                yield() // Force competing checks into the record-read/replace window.
                return snapshot
            }
            override suspend fun putJson(key: String, jsonValue: String) {
                yield()
                json = jsonValue
            }
        }
        var policy = HostAutomaticUpdatePolicy(false, emptySet())
        val service = UpdatePromptService(this,
            api(listOf(UpdateInfo("a", "A", "1", "2"), UpdateInfo("b", "B", "1", "2"))) {
                error("Must not install")
            }, null, notes, storage, { policy })
        service.checkAndPrompt()
        policy = HostAutomaticUpdatePolicy(true, setOf("b"))
        repeat(2) { launch { service.checkAndPrompt() } }
        testScheduler.runCurrent()
        assertEquals(listOf("toast-1"), notes.dismissed)
        assertEquals(listOf("A, B", "B 1 → 2"), notes.shown)
        policy = HostAutomaticUpdatePolicy(false, emptySet())
        service.checkAndPrompt()
        assertEquals("A 1 → 2", notes.shown.last(), "Formerly managed offers are deliberately re-enabled")
    }
}
