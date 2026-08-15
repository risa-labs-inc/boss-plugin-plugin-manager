package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which panel or tab the Toolbox's "Open" button reveals.
 *
 * Tested here rather than through the view because the failure mode is silent
 * and wrong rather than visibly broken: every mismatch still renders a
 * perfectly ordinary button, and a mis-resolved surface opens somebody else's
 * tool. The tiers exist to make "we don't know" a possible answer, so most of
 * these cases are about refusing to guess.
 */
class PanelLaunchTest {
    private fun surface(
        surfaceId: String,
        owner: String = DEFAULT_SURFACE_OWNER,
        displayName: String = surfaceId,
    ) = RegisteredSurface(surfaceId = surfaceId, ownerPluginId = owner, displayName = displayName)

    private fun resolve(
        pluginId: String,
        displayName: String,
        surfaces: List<RegisteredSurface>,
    ) = resolveLaunchSurface(pluginId, displayName, surfaces) { it }

    @Test
    fun `a surface that names its plugin wins outright`() {
        // Docker and Kubernetes pass a real pluginId; nothing needs inferring.
        val docker = surface("docker", owner = "ai.rever.boss.plugin.dynamic.docker", displayName = "Docker")
        assertEquals(
            docker,
            resolve(
                pluginId = "ai.rever.boss.plugin.dynamic.docker",
                displayName = "Docker",
                surfaces = listOf(surface("codebase"), docker, surface("terminal")),
            ),
        )
    }

    @Test
    fun `separators in the surface id do not hide the match`() {
        // The house convention, and the case the old owner-id-only lookup missed:
        // `…dynamic.topofmind` registers `top-of-mind`, `…dynamic.rpaengine`
        // registers `rpa_engine`, and neither passes a plugin id.
        val topOfMind = surface("top-of-mind", displayName = "Top of Mind")
        assertEquals(
            topOfMind,
            resolve(
                "ai.rever.boss.plugin.dynamic.topofmind",
                "Top of Mind",
                listOf(surface("console"), topOfMind),
            ),
        )

        val rpaEngine = surface("rpa_engine", displayName = "RPA Engine")
        assertEquals(
            rpaEngine,
            resolve(
                "ai.rever.boss.plugin.dynamic.rpaengine",
                "RPA Engine",
                listOf(rpaEngine, surface("rpa_recorder", displayName = "RPA Recorder")),
            ),
        )
    }

    @Test
    fun `display name resolves a plugin whose surface id follows no convention`() {
        // flow-tab registers `flow-launcher` — the id says nothing, the name does.
        val launcher = surface("flow-launcher", displayName = "Flow")
        assertEquals(
            launcher,
            resolve(
                "ai.rever.boss.plugin.dynamic.flowtab",
                "Flow",
                listOf(surface("codebase", displayName = "Codebase"), launcher),
            ),
        )
    }

    @Test
    fun `a surface claimed by another plugin is never inferred away from it`() {
        // The whole point of tier 1: an explicit owner is a statement, so the
        // slug coincidence below must not override it.
        assertNull(
            resolve(
                pluginId = "ai.rever.boss.plugin.dynamic.docker",
                displayName = "Docker",
                surfaces = listOf(
                    surface("docker", owner = "com.someone.else.docker", displayName = "Docker"),
                ),
            ),
        )
    }

    @Test
    fun `an ambiguous match resolves to nothing`() {
        // Two unclaimed surfaces called "Docker" — we do not know which, and
        // opening the wrong tool is worse than offering no button.
        assertNull(
            resolve(
                pluginId = "ai.rever.boss.plugin.dynamic.somethingelse",
                displayName = "Docker",
                surfaces = listOf(
                    surface("docker-one", displayName = "Docker"),
                    surface("docker-two", displayName = "Docker"),
                ),
            ),
        )
    }

    @Test
    fun `a plugin with no surface at all is not openable`() {
        // MCP-only plugins and background services register neither panel nor
        // tab. Null is the right answer, and the caller hides the button.
        assertNull(
            resolve(
                "ai.rever.boss.plugin.dynamic.risapambutton",
                "Risa PAM Button",
                listOf(surface("codebase", displayName = "Codebase"), surface("console", displayName = "Console")),
            ),
        )
    }

    @Test
    fun `an empty registry resolves nothing rather than throwing`() {
        assertNull(resolve("ai.rever.boss.plugin.dynamic.codebase", "Codebase", emptyList()))
    }

    @Test
    fun `the resolved element is returned, not a copy of its identity`() {
        // The ViewModel resolves over PanelInfo / TabTypeInfo and then needs the
        // ORIGINAL back, to hand the host the exact registered id it matches on.
        data class Panel(val id: String, val tag: Int)

        val panels = listOf(Panel("console", tag = 1), Panel("git-log", tag = 2))
        assertEquals(
            2,
            resolveLaunchSurface("ai.rever.boss.plugin.dynamic.gitlog", "Git Log", panels) {
                RegisteredSurface(it.id, DEFAULT_SURFACE_OWNER, it.id)
            }?.tag,
        )
    }
}
