package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.resolveLockedUpdateFallback
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.resolveUpdateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class UpdateFallbackTest {
    private val url = "https://github.com/example/plugin"
    private val installed = PluginInfo("example.plugin", "Plugin", "1.0.0", url = url)
    private val success = InstallResult.Success(installed.copy(version = "2.0.0"))

    @Test
    fun `store update refusals cannot reach the homepage fallback`() {
        for (reason in listOf("HTTP 403 permission denied", "HTTP 404", "Requires newer IPC", "Requires newer BOSS")) {
            val refusal = InstallResult.DownloadFailed(reason, storeRefusal = true)
            assertSame(refusal, resolveUpdateSource(updateSourceFor(installed), { refusal }) {
                error("Refused update must not download or replace the installed plugin via GitHub")
            })
        }
    }

    @Test
    fun `outage can recover through the update homepage`() {
        val outage = InstallResult.DownloadFailed("HTTP 503")
        assertSame(success, resolveUpdateSource(updateSourceFor(installed), { outage }) {
            assertEquals(url, it)
            success
        })
        assertSame(outage, resolveUpdateSource(updateSourceFor(installed), { outage }) {
            InstallResult.DownloadFailed("GitHub HTTP 404")
        })
    }

    @Test
    fun `completed or cancelled store update never retries`() {
        for (result in listOf(success, InstallResult.DownloadFailed(DOWNLOAD_CANCELLED))) {
            assertSame(result, resolveUpdateSource(updateSourceFor(installed), { result }) {
                error("A completed or cancelled update must stop")
            })
        }
    }

    @Test
    fun `cancelled update fallback retains cancellation`() {
        val cancelled = InstallResult.DownloadFailed(DOWNLOAD_CANCELLED)
        assertSame(cancelled, resolveUpdateSource(updateSourceFor(installed), {
            InstallResult.DownloadFailed("HTTP 503")
        }) { cancelled })
    }

    @Test
    fun `store update without a fallback retains its failure`() {
        val outage = InstallResult.DownloadFailed("HTTP 503")
        assertSame(outage, resolveUpdateSource(UpdateSource.Store(null), { outage }) {
            error("No fallback URL exists")
        })
    }

    @Test
    fun `explicit GitHub provenance still updates directly`() {
        val source = updateSourceFor(installed.copy(sourceUrl = url))
        assertSame(success, resolveUpdateSource(source, { error("Not a store install") }) {
            assertEquals(url, it)
            success
        })
    }
    @Test
    fun `locked update cannot replace installed bytes after a store 4xx`() {
        for (status in 400..499) {
            assertEquals(
                InstallResult.DownloadFailed("Store download failed: HTTP $status", storeRefusal = true),
                resolveLockedUpdateFallback(status, null, url) {
                    error("Store refusal must not overwrite the locked plugin through GitHub")
                }
            )
        }
    }

    @Test
    fun `locked update outage fallback respects explicit version and preserves errors`() {
        assertSame(success, resolveLockedUpdateFallback(503, null, url) { success })
        val failure = InstallResult.DownloadFailed("Store download failed: HTTP 503")
        assertEquals(failure, resolveLockedUpdateFallback(503, null, url) {
            InstallResult.DownloadFailed("GitHub HTTP 404")
        })
        assertEquals(failure, resolveLockedUpdateFallback(503, "1.0.0", url) {
            error("A version-specific update cannot install latest")
        })
        assertEquals(failure, resolveLockedUpdateFallback(503, null, "") {
            error("No fallback URL exists")
        })
    }

}
