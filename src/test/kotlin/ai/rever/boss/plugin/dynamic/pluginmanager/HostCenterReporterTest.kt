package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.DownloadCenterProvider
import ai.rever.boss.plugin.api.TransferHandle
import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import com.risaboss.toolbox.downloadcenter.HostCenterReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The adapter onto the host's download center - the one implementation that runs on
 * every current host, and the one the earlier rounds' bugs lived in.
 *
 * Both behaviours here are load-bearing: `begin` returning false for a joining call
 * is what stops a fallback path closing its caller's report, and `end` reaching the
 * OWNER's handle is what stops a plugin's buttons reading busy for the rest of the
 * session.
 */
class HostCenterReporterTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** A center that records what it was asked, and can be driven from the test. */
    private class FakeCenter : DownloadCenterProvider {
        val rows = MutableStateFlow<List<TransferInfo>>(emptyList())
        override val transfers: StateFlow<List<TransferInfo>> = rows

        var begins = 0
        val done = mutableListOf<String>()

        override fun begin(
            id: String,
            title: String,
            kind: TransferKind,
            detail: String?,
            onCancel: (() -> Unit)?,
        ): TransferHandle {
            begins++
            // The api's join rule: an id already in flight gets a handle bound to the
            // existing row, whose done() is ALWAYS a no-op.
            val owns = rows.value.none { it.id == id }
            if (owns) {
                rows.value = rows.value + TransferInfo(id = id, title = title, kind = kind, phase = TransferPhase.PREPARING)
            }
            return object : TransferHandle {
                override fun progress(fraction: Float) = Unit

                override fun phase(phase: TransferPhase) = Unit

                override fun detail(text: String?) = Unit

                override fun done() {
                    if (owns) {
                        done += id
                        rows.value = rows.value.filterNot { it.id == id }
                    }
                }
            }
        }
    }

    private fun reporter(center: FakeCenter) = HostCenterReporter(center, scope)

    @Test
    fun `only the first call owns the report`() {
        val center = FakeCenter()
        val reporter = reporter(center)

        assertTrue(reporter.begin("docker", "Docker", isUpdate = false, onCancel = {}))
        assertFalse(reporter.begin("docker", "Docker", isUpdate = false, onCancel = {}), "a joiner owns nothing")
        assertEquals(1, center.begins, "the host is asked once; ownership is decided before it is called")
    }

    @Test
    fun `end closes the owner's handle, so the row actually goes`() {
        val center = FakeCenter()
        val reporter = reporter(center)
        reporter.begin("docker", "Docker", isUpdate = false, onCancel = {})
        reporter.begin("docker", "Docker", isUpdate = false, onCancel = {})

        reporter.end("docker")

        // Keeping the JOINER's handle here was the round-2 bug: its done() is a no-op,
        // so the row never closed and busyIds kept that plugin busy forever.
        assertEquals(listOf("docker"), center.done)
        assertTrue(center.rows.value.isEmpty())
    }

    @Test
    fun `busyIds reports plugin transfers and ignores the rest`() {
        val center = FakeCenter()
        val reporter = reporter(center)
        center.rows.value =
            listOf(
                TransferInfo("a", "A", TransferKind.PLUGIN_INSTALL, TransferPhase.DOWNLOADING),
                TransferInfo("b", "B", TransferKind.PLUGIN_UPDATE, TransferPhase.DOWNLOADING),
                TransferInfo("boss-app-update", "BOSS", TransferKind.APP_UPDATE, TransferPhase.DOWNLOADING),
                TransferInfo("x", "X", TransferKind.OTHER, TransferPhase.DOWNLOADING),
            )

        // The app updating is not a reason for a plugin's Install button to read busy.
        assertEquals(setOf("a", "b"), reporter.busyIds.value)
    }

    @Test
    fun `an update and an install are different kinds to the host`() {
        val center = FakeCenter()
        val reporter = reporter(center)

        reporter.begin("a", "A", isUpdate = true, onCancel = {})
        reporter.begin("b", "B", isUpdate = false, onCancel = {})

        assertEquals(
            listOf(TransferKind.PLUGIN_UPDATE, TransferKind.PLUGIN_INSTALL),
            center.rows.value.map { it.kind },
        )
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() = scope.cancel()
}
