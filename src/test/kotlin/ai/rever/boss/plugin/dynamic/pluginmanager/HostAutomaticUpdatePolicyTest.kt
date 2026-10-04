package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostAutomaticUpdatePolicyTest {
    @Test
    fun `older hosts and disabled mode keep manual prompts`() {
        assertTrue(HostAutomaticUpdatePolicy.parse(null, null).allowsPrompt("plugin"))
        assertTrue(HostAutomaticUpdatePolicy.parse("false", "").allowsPrompt("plugin"))
    }

    @Test
    fun `automatic mode suppresses every prompt except explicit opt outs`() {
        val policy = HostAutomaticUpdatePolicy.parse("TRUE", " a, b , ,")
        assertTrue(policy.allowsPrompt("a"))
        assertTrue(policy.allowsPrompt("b"))
        assertFalse(policy.allowsPrompt("newly-installed"))
        assertFalse(HostAutomaticUpdatePolicy.parse("true", null).allowsPrompt("plugin"))
    }
}
