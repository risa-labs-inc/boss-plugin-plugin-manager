package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginPageUrlTest {

    @Test
    fun `builds the page address under the owning organisation`() {
        assertEquals(
            "https://api.risaboss.com/functions/v1/organisation/o/risa/plugins/" +
                "ai.rever.boss.plugin.dynamic.codexglm",
            PluginPageUrl.forPlugin("risa", "ai.rever.boss.plugin.dynamic.codexglm"),
        )
    }

    @Test
    fun `a plugin with no organisation has no page`() {
        // The normal answer for a sideloaded jar. Returning a URL anyway would put a link on every
        // card that 404s for the ones nobody published.
        assertNull(PluginPageUrl.forPlugin("", "some.plugin"))
        assertNull(PluginPageUrl.forPlugin("   ", "some.plugin"))
        assertNull(PluginPageUrl.forPlugin("risa", ""))
    }

    @Test
    fun `a separator in the id cannot escape its path segment`() {
        // Nothing in the store schema constrains plugin_id to the dotted shape every row happens to
        // have. A `?` would end the path and a `/` would address a different plugin entirely.
        val url = PluginPageUrl.forPlugin("risa", "a/b?c#d e")!!
        assertEquals("a%2Fb%3Fc%23d%20e", url.substringAfter("/plugins/"), "the id escaped its segment")
        // The properties that matter, stated separately from the exact spelling above: what follows
        // /plugins/ is ONE segment, and nothing in it can start a query or a fragment.
        val segment = url.substringAfter("/plugins/")
        assertTrue(!segment.contains('/'), "the id introduced another path segment: $segment")
        assertTrue(!segment.contains('?') && !segment.contains('#'), "the id can end the path: $segment")
    }

    @Test
    fun `a space becomes percent-twenty, never a plus`() {
        // URLEncoder would write `+` here, which in a PATH is a literal plus rather than a space.
        val url = PluginPageUrl.forPlugin("risa", "two words")!!
        assertTrue(url.endsWith("two%20words"), "form-encoded rather than path-encoded: $url")
        assertTrue(!url.contains("+"), "a plus reached the path: $url")
    }

    @Test
    fun `non-ascii is encoded as utf-8 bytes`() {
        val url = PluginPageUrl.forPlugin("risa", "café")!!
        assertTrue(url.endsWith("caf%C3%A9"), "expected utf-8 percent encoding, got $url")
    }

    @Test
    fun `unreserved characters are left alone`() {
        // A dotted reverse-DNS id must survive unchanged, or every link on every card changes shape
        // for no reason and stops matching what the page expects.
        val id = "ai.rever.boss.plugin.dynamic.tool-creator_v2~x"
        assertTrue(PluginPageUrl.forPlugin("risa", id)!!.endsWith("/plugins/$id"))
    }

    @Test
    fun `the base is overridable so the address is not welded to production`() {
        assertEquals(
            "http://localhost:54321/functions/v1/organisation/o/boss/plugins/x.y",
            PluginPageUrl.forPlugin("boss", "x.y", "http://localhost:54321/functions/v1/organisation"),
        )
    }

    @Test
    fun `the installed hint is passed only when the caller knows it`() {
        // The page renders Open or Install from this. Absent means "I do not know", which is a
        // third state and not the same as "not installed" - a visitor arriving from a shared link
        // must not be told to install something they already have.
        assertTrue(PluginPageUrl.forPlugin("risa", "x.y", installed = true)!!.endsWith("?installed=1"))
        assertTrue(PluginPageUrl.forPlugin("risa", "x.y", installed = false)!!.endsWith("?installed=0"))
        assertTrue(!PluginPageUrl.forPlugin("risa", "x.y")!!.contains("installed"))
    }

    @Test
    fun `the hint does not disturb the encoded path`() {
        val url = PluginPageUrl.forPlugin("risa", "a b", installed = true)!!
        assertTrue(url.endsWith("/plugins/a%20b?installed=1"), url)
    }

    @Test
    fun `a handoff token rides along with the installed hint`() {
        // Both matter and both must survive: the page exchanges `t` for a session and redirects to
        // the SAME url minus that one parameter, so anything else on it has to still be there
        // afterwards or the label reverts to "unknown" the moment you sign in.
        val url = PluginPageUrl.forPlugin("risa", "x.y", installed = true, handoffToken = "tok123")!!
        assertTrue(url.contains("t=tok123"), url)
        assertTrue(url.contains("installed=1"), url)
        assertTrue(url.contains("?"), url)
        assertEquals(1, url.count { it == '?' }, "more than one query separator: $url")
    }

    @Test
    fun `no token means no t parameter at all`() {
        // An empty `t=` is worse than none: the page would try to exchange it and refuse.
        assertTrue(!PluginPageUrl.forPlugin("risa", "x.y", handoffToken = null)!!.contains("t="))
        assertTrue(!PluginPageUrl.forPlugin("risa", "x.y", handoffToken = "")!!.contains("t="))
        assertTrue(!PluginPageUrl.forPlugin("risa", "x.y", handoffToken = "  ")!!.contains("t="))
    }

    @Test
    fun `a token is encoded into the query`() {
        // Tokens are url-safe base64 today, so this changes nothing now; it means a token that ever
        // carries a + or / cannot end the parameter early.
        val url = PluginPageUrl.forPlugin("risa", "x.y", handoffToken = "a+b/c=")!!
        assertTrue(url.contains("t=a%2Bb%2Fc%3D"), url)
    }
}
