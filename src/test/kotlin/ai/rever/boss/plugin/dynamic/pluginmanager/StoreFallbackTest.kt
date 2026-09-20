package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When a store install may fall back to GitHub, and what the user is told when it cannot (#52).
 *
 * The failure this pins: installing a freshly published plugin reported
 * `Download failed: Could not fetch release: HTTP 404`, and an operator spent an hour on the
 * private repo that 404 pointed at. The store had answered **403** - "requires permission
 * agenthq.use, ask an admin" - and the GitHub fallback overwrote it with an error about a
 * repo nobody was supposed to be installing from.
 *
 * Two separate claims live here. A refusal is an answer, so it must not be routed around; and
 * when the store is genuinely down, the message that leads is still the store's.
 */
class StoreFallbackTest {

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------- a refusal is an answer

    @Test
    fun `a permission refusal is not routed around`() {
        // The exact case from the issue. A GitHub download here would also install the jar the
        // store just declined to hand over, which makes the gate decorative.
        assertFalse(mayFallBackToGitHub(403))
    }

    @Test
    fun `every 4xx is the store answering`() {
        listOf(400, 401, 403, 404, 409, 410, 422, 451, 499).forEach {
            assertFalse(mayFallBackToGitHub(it), "HTTP $it should not fall back to GitHub")
        }
    }

    @Test
    fun `an outage leaves room for the fallback`() {
        listOf(500, 502, 503, 504).forEach {
            assertTrue(mayFallBackToGitHub(it), "HTTP $it is the store failing to answer")
        }
    }

    @Test
    fun `a request that never got a status falls back`() {
        // A connection reset or a timeout: the store said nothing, so nothing was refused.
        assertTrue(mayFallBackToGitHub(null))
    }

    @Test
    fun `a 3xx is treated as an outage rather than a refusal`() {
        // Not expected in practice - the client follows redirects - but if one arrives it is
        // not a decision about this install, so it must not be read as one.
        assertTrue(mayFallBackToGitHub(302))
    }

    // ------------------------------------------------- which error the user sees

    @Test
    fun `the store's reason leads when both fail`() {
        val message = installFailureMessage(
            storeError = "This plugin requires permission(s): agenthq.use. Ask an admin to grant them.",
            githubError = "Could not fetch release: HTTP 404",
        )

        assertTrue(message.startsWith("This plugin requires permission(s): agenthq.use"), message)
        // Kept, not dropped: when the store is down, "the fallback failed too, and here is why"
        // is the difference between one failed install and two silent ones.
        assertTrue(message.contains("Could not fetch release: HTTP 404"), message)
    }

    @Test
    fun `a missing half is not decorated`() {
        assertEquals("store is unreachable", installFailureMessage("store is unreachable", ""))
        assertEquals("github says no", installFailureMessage("", "github says no"))
    }

    // ------------------------------------------------------- the whole decision

    private val refused = InstallResult.DownloadFailed(
        "This plugin requires permission(s): agenthq.use. Ask an admin to grant them.",
    )
    private val storeDown = InstallResult.DownloadFailed("Store download failed: HTTP 503 - null")
    private val installed = InstallResult.Success(
        PluginInfo(pluginId = "ai.rever.boss.plugin.dynamic.agenthq", displayName = "Agent HQ", version = "0.1.1"),
    )

    /** Records whether the fallback was reached at all, which is half of what is being claimed. */
    private class Fallback(private val answer: InstallResult?) : suspend () -> InstallResult? {
        var called = false
            private set

        override suspend fun invoke(): InstallResult? {
            called = true
            return answer
        }
    }

    private fun outcome(
        storeResult: InstallResult,
        storeStatus: Int?,
        fallback: Fallback,
    ): InstallResult = runBlocking {
        installOutcome(storeResult, storeStatus, isCancelled = { false }, tryGitHub = fallback)
    }

    @Test
    fun `a refused install is reported as the store refused it`() {
        // The bug, end to end: the store's 403 must reach the user, and GitHub must not be
        // asked for the jar the store just declined to hand over.
        val fallback = Fallback(installed)

        val result = outcome(refused, storeStatus = 403, fallback = fallback)

        assertEquals(refused, result)
        assertFalse(fallback.called, "a refusal was routed around")
    }

    @Test
    fun `an outage tries GitHub and takes the install`() {
        val fallback = Fallback(installed)

        val result = outcome(storeDown, storeStatus = 503, fallback = fallback)

        assertTrue(fallback.called)
        assertEquals(installed, result)
    }

    @Test
    fun `when both fail the store's reason leads`() {
        val fallback = Fallback(InstallResult.DownloadFailed("Could not fetch release: HTTP 404"))

        val result = outcome(storeDown, storeStatus = null, fallback = fallback)

        val error = (result as InstallResult.DownloadFailed).error
        assertTrue(error.startsWith("Store download failed: HTTP 503"), error)
        assertTrue(error.contains("Could not fetch release: HTTP 404"), error)
    }

    @Test
    fun `no GitHub source leaves the store's error alone`() {
        val fallback = Fallback(null)

        val result = outcome(storeDown, storeStatus = 503, fallback = fallback)

        assertTrue(fallback.called)
        assertEquals(storeDown, result)
    }

    @Test
    fun `a jar that downloaded and would not load is not re-fetched from GitHub`() {
        // The store served the bytes; the host refused them. A second download of the same
        // release cannot end differently, and the load error is what says something useful.
        val loadFailed = InstallResult.LoadFailed("Failed to load plugin 'x' (see app logs for details)")
        val fallback = Fallback(installed)

        val result = outcome(loadFailed, storeStatus = null, fallback = fallback)

        assertEquals(loadFailed, result)
        assertFalse(fallback.called)
    }

    @Test
    fun `a cancelled download is not retried from GitHub`() = runBlocking {
        // Falling through here would open a second connection for the jar the user just stopped.
        val cancelled = InstallResult.DownloadFailed(DOWNLOAD_CANCELLED)
        val fallback = Fallback(installed)

        val result = installOutcome(cancelled, null, isCancelled = { it.wasCancelled() }, tryGitHub = fallback)

        assertEquals(cancelled, result)
        assertFalse(fallback.called)
    }

    @Test
    fun `a successful store install never asks GitHub`() {
        val fallback = Fallback(installed)

        val result = outcome(installed, storeStatus = null, fallback = fallback)

        assertEquals(installed, result)
        assertFalse(fallback.called)
    }

    // ----------------------------------------------------- picking the jar asset

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
        // browser_download_url answers 404 on a private repo however good the token is: the
        // token only travels on an api.github.com request.
        val jar = jarAssetIn(json.decodeFromString(releaseJson), isAuthenticated = true)

        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", jar?.url)
        assertEquals("boss-plugin-agent-hq-1.2.3.jar", jar?.fileName)
        assertTrue(jar!!.viaAssetApi, "an authenticated download must ask for the bytes explicitly")
    }

    @Test
    fun `an anonymous caller keeps the browser url`() {
        val jar = jarAssetIn(json.decodeFromString(releaseJson), isAuthenticated = false)

        assertEquals(
            "https://github.com/o/r/releases/download/v1.2.3/boss-plugin-agent-hq-1.2.3.jar",
            jar?.url,
        )
        assertFalse(jar!!.viaAssetApi)
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

    // ------------------------------------------------------------ the token

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
