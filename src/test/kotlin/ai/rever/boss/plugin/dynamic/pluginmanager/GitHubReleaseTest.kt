package ai.rever.boss.plugin.dynamic.pluginmanager

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the GitHub fallback can actually reach the jar it is falling back to (#52).
 *
 * For the plugins this matters for - `boss-plugin-fluck-agent`, `boss-plugin-ai-gateway`,
 * `boss-plugin-agent-hq` - the repo is private, and an anonymous `api.github.com` call answers
 * 404 for a private repo whatever exists inside it. So the fallback could not succeed, and the
 * 404 it produced described a repo the operator was never meant to be installing from.
 *
 * A token alone is not enough: `browser_download_url` answers 404 on a private repo however good
 * the token is, because the token only travels on an `api.github.com` request. Which url the
 * download uses is therefore part of the fix, not a detail of it.
 */
class GitHubReleaseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val releaseJson = """
        {
          "tag_name": "v1.2.3",
          "assets": [
            {
              "name": "notes.txt",
              "url": "https://api.github.com/repos/o/r/releases/assets/1",
              "browser_download_url": "https://github.com/o/r/releases/download/v1.2.3/notes.txt"
            },
            {
              "name": "boss-plugin-agent-hq-1.2.3.jar",
              "url": "https://api.github.com/repos/o/r/releases/assets/2",
              "browser_download_url": "https://github.com/o/r/releases/download/v1.2.3/boss-plugin-agent-hq-1.2.3.jar"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an authenticated caller is sent to the asset API`() {
        val jar = jarAssetIn(json.decodeFromString(releaseJson), isAuthenticated = true)

        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", jar?.url)
        assertEquals("boss-plugin-agent-hq-1.2.3.jar", jar?.fileName)
        assertTrue(jar!!.viaAssetApi, "an authenticated download must ask for the bytes explicitly")
    }

    @Test
    fun `an anonymous caller keeps the browser url`() {
        // No token, so the asset API would answer with the asset's JSON rather than the jar, and
        // an Accept header nothing will honour is worse than not sending one.
        val jar = jarAssetIn(json.decodeFromString(releaseJson), isAuthenticated = false)

        assertEquals(
            "https://github.com/o/r/releases/download/v1.2.3/boss-plugin-agent-hq-1.2.3.jar",
            jar?.url,
        )
        assertFalse(jar!!.viaAssetApi)
    }

    @Test
    fun `the jar is picked out of a release that holds other assets`() {
        // The old regex took the first `browser_download_url` ending in .jar wherever it appeared
        // in the document; this has to make the same choice from the parsed assets.
        val jar = jarAssetIn(json.decodeFromString(releaseJson), isAuthenticated = false)

        assertEquals("boss-plugin-agent-hq-1.2.3.jar", jar?.fileName)
    }

    @Test
    fun `a release with no jar is not a download`() {
        val noJar = """{ "assets": [{ "name": "notes.txt", "browser_download_url": "https://x/notes.txt" }] }"""

        assertNull(jarAssetIn(json.decodeFromString(noJar), isAuthenticated = true))
        assertNull(jarAssetIn(GitHubRelease(), isAuthenticated = false))
    }

    @Test
    fun `an asset named only by its url is still found`() {
        // Older releases, and anything published by hand, can carry an empty name field.
        val unnamed = """
            {
              "assets": [
                { "name": "", "browser_download_url": "https://github.com/o/r/releases/download/v1/plugin.jar" }
              ]
            }
        """.trimIndent()

        val jar = jarAssetIn(json.decodeFromString(unnamed), isAuthenticated = false)

        assertEquals("plugin.jar", jar?.fileName)
    }

    @Test
    fun `a token is read from either variable, GITHUB_TOKEN first`() {
        assertEquals("a", githubToken(mapOf("GITHUB_TOKEN" to "a", "GH_TOKEN" to "b")::get))
        assertEquals("b", githubToken(mapOf("GH_TOKEN" to "b")::get))
    }

    @Test
    fun `an unset or blank variable is no token`() {
        // Blank matters: an empty GITHUB_TOKEN in a shell profile would otherwise send
        // `Authorization: Bearer ` and turn a working anonymous fetch into a 401.
        assertNull(githubToken { null })
        assertNull(githubToken(mapOf("GITHUB_TOKEN" to "   ")::get))
        assertEquals("real", githubToken(mapOf("GITHUB_TOKEN" to " ", "GH_TOKEN" to "real")::get))
    }
}
