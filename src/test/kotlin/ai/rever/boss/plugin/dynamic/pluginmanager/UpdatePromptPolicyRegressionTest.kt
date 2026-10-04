package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatePromptPolicyRegressionTest {
    private class Notes : NotificationProvider {
        val shown = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var action: (() -> Unit)? = null
        override fun showToast(message: String, type: NotificationType, duration: NotificationDuration,
            title: String?, actionLabel: String?, onAction: (() -> Unit)?): String {
            shown += message
            action = onAction
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
    }
}
