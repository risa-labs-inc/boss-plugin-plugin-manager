package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginInfo
import ai.rever.boss.plugin.dynamic.pluginmanager.impl.resolveStoreFallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The store -> GitHub fallback policy, tested as a pure function (the network work lives in the
 * lambda the installer passes). Both failure modes this pins were real: falling back on a store
 * REFUSAL masked the store's actionable error with an unauthenticated-GitHub 404 AND bypassed the
 * gate the store enforced; and even for a genuine outage, the GitHub error - not the store's - was
 * the one that surfaced.
 */
class StoreFallbackTest {
    private fun success() = InstallResult.Success(PluginInfo("a.b.c", "Fake Plugin", "1.0.0"))

    @Test
    fun `a store refusal is returned as-is and never falls back to GitHub`() {
        var fellBack = false
        val store = InstallResult.DownloadFailed("Requires permission agenthq.use.", storeRefusal = true)

        val result = resolveStoreFallback(store) {
            fellBack = true
            success()
        }

        assertFalse(fellBack, "GitHub fallback must not run on a store refusal")
        assertEquals(store, result)
    }

    @Test
    fun `a store outage falls back, and a successful GitHub install is returned`() {
        val store = InstallResult.DownloadFailed("Store download failed: HTTP 503")
        val ok = success()

        assertEquals(ok, resolveStoreFallback(store) { ok })
    }

    @Test
    fun `when the GitHub fallback also fails, the store error is kept, not GitHub's`() {
        val store = InstallResult.DownloadFailed("Store download failed: HTTP 503")

        val result = resolveStoreFallback(store) {
            InstallResult.DownloadFailed("Could not fetch release: HTTP 404")
        }

        assertEquals(store, result)
    }

    @Test
    fun `a cancelled GitHub fallback is returned as the cancellation, not the store error`() {
        val store = InstallResult.DownloadFailed("Store download failed: HTTP 503")
        val cancelled = InstallResult.DownloadFailed(DOWNLOAD_CANCELLED)

        assertEquals(cancelled, resolveStoreFallback(store) { cancelled })
    }
}
