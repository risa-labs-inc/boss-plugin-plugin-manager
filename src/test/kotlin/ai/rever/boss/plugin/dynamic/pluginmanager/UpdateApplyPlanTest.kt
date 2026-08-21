package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a just-applied update still needs, and in particular that Toolbox updating ITSELF no
 * longer demands a restart.
 *
 * This had no coverage at all, which is why the stale "restart only" entry for Toolbox outlived
 * the host fix that made it unnecessary by six weeks: nothing failed when the reason expired.
 */
class UpdateApplyPlanTest {

    private companion object {
        const val OTHER = "ai.rever.boss.plugin.dynamic.somethingelse"
        const val MICROKERNEL = "ai.rever.boss.microkernel.runtime"
    }

    /** A delegate that reports whatever the test says is loaded, and no running instances. */
    private class FakeDelegate(
        private val loaded: List<LoadedPluginInfo>,
        private val instances: Map<String, Int> = emptyMap(),
    ) : PluginLoaderDelegate {
        override suspend fun loadPlugin(jarPath: String): LoadedPluginInfo? = null
        override suspend fun unloadPlugin(pluginId: String): Boolean = true
        override suspend fun reloadPlugin(pluginId: String): LoadedPluginInfo? = null
        override fun getLoadedPlugins(): List<LoadedPluginInfo> = loaded
        override fun isPluginLoaded(pluginId: String): Boolean = loaded.any { it.pluginId == pluginId }
        override fun getPluginsDirectory(): String = "/tmp/boss-test-plugins"
        override fun getBundledPluginsDirectory(): String = "/tmp/boss-test-bundled"
        override fun isCurrentUserAdmin(): Boolean = false
        override suspend fun enablePlugin(pluginId: String): Boolean = true
        override suspend fun disablePlugin(pluginId: String): Boolean = true
        override fun getAccessToken(): String = ""
        override fun getRunningInstanceCount(pluginId: String): Int = instances[pluginId] ?: 0
        override fun getInaccessiblePlugins(): List<InaccessiblePluginInfo> = emptyList()
    }

    private fun locked(pluginId: String, name: String) = LoadedPluginInfo(
        pluginId = pluginId,
        displayName = name,
        version = "1.0.0",
        jarPath = "/tmp/boss-test-plugins/${pluginId.replace('.', '_')}.jar",
        isSystemPlugin = true,
        canUnload = false,
    )

    // -----------------------------------------------------------------------
    // Toolbox updating itself
    // -----------------------------------------------------------------------

    @Test
    fun `toolbox with nothing open hot-reloads instead of asking for a restart`() {
        val delegate = FakeDelegate(listOf(locked(TOOLBOX_PLUGIN_ID, "Toolbox")))
        val plan = buildUpdateApplyPlan(listOf(TOOLBOX_PLUGIN_ID), delegate)
        assertTrue(
            plan is UpdateApplyPlan.Reload,
            "Toolbox is locked but the host reloads in a detached scope, so its own update " +
                "applies in place. Got $plan",
        )
        assertEquals(listOf(TOOLBOX_PLUGIN_ID), plan.pluginIds)
    }

    @Test
    fun `toolbox with its panel open asks to reset, not to restart`() {
        // The realistic case: the user is looking at Toolbox when they press Update, so it has a
        // running instance. Reset force-reloads, which applies the update.
        val delegate = FakeDelegate(
            listOf(locked(TOOLBOX_PLUGIN_ID, "Toolbox")),
            instances = mapOf(TOOLBOX_PLUGIN_ID to 1),
        )
        val plan = buildUpdateApplyPlan(listOf(TOOLBOX_PLUGIN_ID), delegate)
        assertTrue(plan is UpdateApplyPlan.Reset, "expected Reset, got $plan")
        assertEquals(1, plan.instanceCount)
    }

    // -----------------------------------------------------------------------
    // What must still demand a restart
    // -----------------------------------------------------------------------

    @Test
    fun `the microkernel runtime still only applies on a restart`() {
        // Not a loadable plugin at all - a classpath component. No reload can apply it.
        val delegate = FakeDelegate(listOf(locked(MICROKERNEL, "Microkernel Runtime")))
        val plan = buildUpdateApplyPlan(listOf(MICROKERNEL), delegate)
        assertTrue(plan is UpdateApplyPlan.Restart, "expected Restart, got $plan")
    }

    @Test
    fun `the api plugin is offered as an api-layer swap, not a restart`() {
        val delegate = FakeDelegate(listOf(locked(API_PLUGIN_ID, "BOSS Plugin API")))
        val plan = buildUpdateApplyPlan(listOf(API_PLUGIN_ID), delegate)
        assertTrue(plan is UpdateApplyPlan.SwapApiLayer, "expected SwapApiLayer, got $plan")
    }

    @Test
    fun `a batch containing the microkernel still restarts even alongside toolbox`() {
        // Restart wins: one member of the batch cannot be applied any other way.
        val delegate = FakeDelegate(
            listOf(locked(TOOLBOX_PLUGIN_ID, "Toolbox"), locked(MICROKERNEL, "Microkernel Runtime")),
        )
        val plan = buildUpdateApplyPlan(listOf(TOOLBOX_PLUGIN_ID, MICROKERNEL), delegate)
        assertTrue(plan is UpdateApplyPlan.Restart, "expected Restart, got $plan")
        assertEquals(listOf(MICROKERNEL), plan.pluginIds)
    }

    // -----------------------------------------------------------------------
    // selfLast: the ordering the executors depend on
    // -----------------------------------------------------------------------

    @Test
    fun `selfLast separates toolbox so it can be applied after everything else`() {
        val (others, includesSelf) = selfLast(listOf(OTHER, TOOLBOX_PLUGIN_ID, MICROKERNEL))
        assertTrue(includesSelf)
        assertEquals(listOf(OTHER, MICROKERNEL), others)
        assertFalse(
            TOOLBOX_PLUGIN_ID in others,
            "Toolbox must not be in the reported batch: applying it cancels the coroutine doing " +
                "the reporting, so its result can never be read",
        )
    }

    @Test
    fun `selfLast leaves a batch without toolbox untouched`() {
        val ids = listOf(OTHER, MICROKERNEL)
        val (others, includesSelf) = selfLast(ids)
        assertFalse(includesSelf)
        assertEquals(ids, others, "order matters: the executors apply these in sequence")
    }

    @Test
    fun `selfLast handles a batch that is only toolbox`() {
        val (others, includesSelf) = selfLast(listOf(TOOLBOX_PLUGIN_ID))
        assertTrue(includesSelf)
        assertTrue(others.isEmpty(), "nothing to report on, so nothing should be attempted first")
    }
}
