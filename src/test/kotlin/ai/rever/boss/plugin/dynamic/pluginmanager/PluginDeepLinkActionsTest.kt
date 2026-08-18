package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parameter gate on the deep-link handler.
 *
 * `boss://` is registered with the OS, so any page that can ask the OS to open a URL reaches this
 * code with a string of its choosing. That string is built into a plugin-store request and shown
 * to the user in an install prompt, so it is checked for shape before either happens.
 */
class PluginDeepLinkActionsTest {

    @Test
    fun `real plugin ids are accepted`() {
        for (
            id in listOf(
                "ai.rever.boss.plugin.dynamic.codexglm",
                "ai.rever.boss.plugin.dynamic.tool-creator",
                "ai.rever.boss.plugin.api",
            )
        ) {
            assertTrue(PluginDeepLinkActions.isPlausiblePluginId(id), id)
        }
    }

    @Test
    fun `anything that could steer a request elsewhere is refused`() {
        for (
            id in listOf(
                "",
                "  ",
                "ab",
                "../../etc/passwd",
                "a/b",
                "a?b",
                "a#b",
                "a b",
                "http://evil.test/x",
                "-leading-dash",
                "a".repeat(200),
            )
        ) {
            assertFalse(PluginDeepLinkActions.isPlausiblePluginId(id), "accepted: $id")
        }
    }
}
