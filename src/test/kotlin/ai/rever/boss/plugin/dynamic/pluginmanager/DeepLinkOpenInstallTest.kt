package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.dynamic.pluginmanager.api.ExtractedManifest
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginEvent
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginStoreItem
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginVersionInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UninstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the deep link decides, driven through the REAL handler.
 *
 * Written from a report: pressing "Open in BOSS" on an installed plugin installed it, over and
 * over. The cause was reading installed-state from a list the panel fills - a deep link arrives
 * with no panel open, so a plainly installed plugin could read as absent and `open` then offered
 * to install it.
 *
 * The fix is an ORDER: refresh, then decide. An order reverts silently, so it is pinned here. The
 * first version of this file tested a hand-written COPY of that decision, which would have passed
 * against the broken handler - the whole point is to exercise the object that ships.
 */
class DeepLinkOpenInstallTest {

    private class FakeApi(
        /** What a refresh discovers. Empty until then, exactly like the real cache. */
        private val onRefresh: List<PluginInfo>,
        private val storeHas: Boolean = true,
    ) : PluginManagerAPI {
        val installedCalls = mutableListOf<String>()
        var refreshes = 0
        private var visible: List<PluginInfo> = emptyList()

        fun refresh() {
            refreshes += 1
            visible = onRefresh
        }

        override fun getInstalledPlugins(): List<PluginInfo> = visible
        override fun isPluginInstalled(pluginId: String) = visible.any { it.pluginId == pluginId }
        override fun getInstalledPlugin(pluginId: String) = visible.find { it.pluginId == pluginId }
        override fun observeInstalledPlugins(): StateFlow<List<PluginInfo>> = MutableStateFlow(visible)

        override suspend fun fetchPluginDetails(pluginId: String): Result<PluginStoreItem> =
            if (storeHas) {
                Result.success(
                    PluginStoreItem(
                        pluginId = pluginId,
                        displayName = "Fake Plugin",
                        version = "1.0.0",
                    ),
                )
            } else {
                Result.failure(IllegalStateException("not in store"))
            }

        override suspend fun installPlugin(pluginId: String): InstallResult {
            installedCalls += pluginId
            return InstallResult.Success(PluginInfo(pluginId, "Fake Plugin", "1.0.0"))
        }

        // Not reached by these tests. Failing loudly beats a silent default that would let a
        // future change take one of these paths without anybody noticing.
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
        override fun observeEvents(): Flow<PluginEvent> = emptyFlow()
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

    private class FakeNotifications : NotificationProvider {
        val toasts = mutableListOf<String>()
        var lastAction: (() -> Unit)? = null

        override fun showToast(
            message: String,
            type: NotificationType,
            duration: NotificationDuration,
            title: String?,
            actionLabel: String?,
            onAction: (() -> Unit)?,
        ): String {
            toasts += message
            lastAction = onAction
            return "id"
        }

        override fun dismiss(notificationId: String) {}

        override fun dismissAll() {}
    }

    /** Records what the dialog was asked, and answers with [confirmAnswer]. */
    private class FakeDialogs(val confirmAnswer: Boolean) {
        val asked = mutableListOf<Pair<String, String>>()
    }

    private fun handler(
        api: FakeApi,
        notes: FakeNotifications,
        revealed: MutableList<String>,
        canReveal: Boolean = true,
        dialogs: FakeDialogs? = null,
    ) = PluginDeepLinkActions(
        handlerId = "toolbox",
        api = api,
        notifications = notes,
        // Unconfined so the handler's launch runs inline: the assertions are about what it decided,
        // not about scheduling.
        scope = CoroutineScope(Dispatchers.Unconfined),
        revealPlugin = { id -> revealed += id; canReveal },
        refreshInstalled = { api.refresh() },
        confirmInstall = dialogs?.let { d ->
            { title: String, message: String -> d.asked += title to message; d.confirmAnswer }
        },
    )

    private val installed = listOf(PluginInfo("a.b.c", "Fake Plugin", "1.0.0"))

    @Test
    fun `open on an installed plugin reveals it and never installs`() {
        val api = FakeApi(onRefresh = installed)
        val notes = FakeNotifications()
        val revealed = mutableListOf<String>()

        handler(api, notes, revealed).handle("open", mapOf("plugin" to "a.b.c"))

        assertEquals(listOf("a.b.c"), revealed)
        assertTrue(api.installedCalls.isEmpty(), "open installed something: ${api.installedCalls}")
    }

    @Test
    fun `the decision is taken after a refresh, which is the whole bug`() {
        // FakeApi reports nothing installed until refresh() runs, which is exactly how the real
        // cache behaves before the panel has ever been opened. A handler that decided first would
        // fall through to install.
        val api = FakeApi(onRefresh = installed)
        val revealed = mutableListOf<String>()

        handler(api, FakeNotifications(), revealed).handle("open", mapOf("plugin" to "a.b.c"))

        assertEquals(1, api.refreshes, "decided without refreshing first")
        assertEquals(listOf("a.b.c"), revealed)
    }

    @Test
    fun `install on an installed plugin does not reinstall`() {
        val api = FakeApi(onRefresh = installed)
        val notes = FakeNotifications()

        handler(api, notes, mutableListOf()).handle("install", mapOf("plugin" to "a.b.c"))

        assertTrue(api.installedCalls.isEmpty(), "reinstalled: ${api.installedCalls}")
        assertTrue(notes.toasts.any { it.contains("already installed") }, notes.toasts.toString())
    }

    @Test
    fun `install on an absent plugin asks first and installs only on the press`() {
        val api = FakeApi(onRefresh = emptyList())
        val notes = FakeNotifications()

        handler(api, notes, mutableListOf()).handle("install", mapOf("plugin" to "a.b.c"))

        assertTrue(api.installedCalls.isEmpty(), "installed without being asked")
        assertTrue(notes.toasts.any { it.startsWith("Install ") }, notes.toasts.toString())

        notes.lastAction!!.invoke()
        assertEquals(listOf("a.b.c"), api.installedCalls)
    }

    @Test
    fun `an id the store does not have is refused before anything is offered`() {
        val api = FakeApi(onRefresh = emptyList(), storeHas = false)
        val notes = FakeNotifications()

        handler(api, notes, mutableListOf()).handle("install", mapOf("plugin" to "a.b.c"))

        assertTrue(api.installedCalls.isEmpty())
        assertTrue(notes.toasts.any { it.contains("No plugin with that id") }, notes.toasts.toString())
    }

    @Test
    fun `open says so rather than installing when it cannot reveal`() {
        val api = FakeApi(onRefresh = installed)
        val notes = FakeNotifications()

        handler(api, notes, mutableListOf(), canReveal = false).handle("open", mapOf("plugin" to "a.b.c"))

        assertTrue(api.installedCalls.isEmpty(), "a failed reveal fell through to installing")
        assertTrue(notes.toasts.any { it.contains("Open it from the Toolbox") }, notes.toasts.toString())
    }

    @Test
    fun `an unknown verb and a malformed id are refused`() {
        val api = FakeApi(onRefresh = installed)
        val h = handler(api, FakeNotifications(), mutableListOf())
        assertEquals(false, h.handle("uninstall", mapOf("plugin" to "a.b.c")))
        assertEquals(false, h.handle("open", mapOf("plugin" to "a/b")))
        assertEquals(false, h.handle("open", emptyMap()))
        assertEquals(0, api.refreshes, "a refused link still touched the host")
    }

    @Test
    fun `install asks in a dialog, and installs only when it is confirmed`() {
        // A dialog, not a toast: this question's answer runs somebody else's code, and a toast can
        // be missed entirely, times out on its own, and makes "dismiss" and "no" the same gesture.
        val api = FakeApi(onRefresh = emptyList())
        val notes = FakeNotifications()
        val dialogs = FakeDialogs(confirmAnswer = true)

        handler(api, notes, mutableListOf(), dialogs = dialogs).handle("install", mapOf("plugin" to "a.b.c"))

        assertEquals(1, dialogs.asked.size, "no dialog was shown")
        assertTrue(dialogs.asked[0].first.contains("Fake Plugin"), dialogs.asked[0].first)
        // The id is in the message: display names are publisher-chosen and two can read alike.
        assertTrue(dialogs.asked[0].second.contains("a.b.c"), dialogs.asked[0].second)
        assertEquals(listOf("a.b.c"), api.installedCalls)
        // No toast prompt when a dialog was available - one question, asked once.
        assertTrue(notes.toasts.none { it.startsWith("Install ") }, notes.toasts.toString())
    }

    @Test
    fun `declining the dialog installs nothing`() {
        val api = FakeApi(onRefresh = emptyList())
        val dialogs = FakeDialogs(confirmAnswer = false)

        handler(api, FakeNotifications(), mutableListOf(), dialogs = dialogs).handle("install", mapOf("plugin" to "a.b.c"))

        assertEquals(1, dialogs.asked.size)
        assertTrue(api.installedCalls.isEmpty(), "installed after being declined: ${api.installedCalls}")
    }

    @Test
    fun `open on an absent plugin asks in the dialog too`() {
        val api = FakeApi(onRefresh = emptyList())
        val dialogs = FakeDialogs(confirmAnswer = true)

        handler(api, FakeNotifications(), mutableListOf(), dialogs = dialogs).handle("open", mapOf("plugin" to "a.b.c"))

        assertEquals(1, dialogs.asked.size, "open fell back to the toast")
    }

    @Test
    fun `a host with no dialog provider still works, via the toast`() {
        // Providers may be absent; a plugin degrades rather than losing the feature.
        val api = FakeApi(onRefresh = emptyList())
        val notes = FakeNotifications()

        handler(api, notes, mutableListOf(), dialogs = null).handle("install", mapOf("plugin" to "a.b.c"))

        assertTrue(notes.toasts.any { it.startsWith("Install ") }, notes.toasts.toString())
        assertTrue(api.installedCalls.isEmpty(), "installed without asking")
        notes.lastAction!!.invoke()
        assertEquals(listOf("a.b.c"), api.installedCalls)
    }
}
