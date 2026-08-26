package ai.rever.boss.plugin.dynamic.pluginmanager

import com.risaboss.toolbox.downloadcenter.TransferReporter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read loop, which is where cancelling actually happens.
 *
 * Cancel could not be a coroutine cancellation here: `InputStream.read` does not
 * observe one, so the file kept downloading, and when the cancellation did surface
 * it surfaced through call sites that clear their busy flag AFTER the call. So the
 * loop checks a flag per chunk - and everything about that is worth pinning,
 * because none of it is visible from the outcome types alone.
 */
class DownloadLoopCancelTest {
    @TempDir
    lateinit var dir: File

    /** Records what the loop reported, and can cancel from inside it. */
    private class FakeReporter : TransferReporter {
        override val busyIds: StateFlow<Set<String>> = MutableStateFlow(emptySet())

        val phases = mutableListOf<String>()
        var progressCalls = 0

        /** Invoked on every progress report, so a test can cancel mid-stream. */
        var onProgress: (() -> Unit)? = null
        var cancelAction: (() -> Unit)? = null

        /**
         * Make the next `begin` throw, standing in for the host.
         *
         * `HostCenterReporter.begin` calls into `DownloadCenterProvider.begin` inside a
         * `computeIfAbsent`, so this is host code and a throw is reachable.
         */
        var failNextBegin = false

        override fun begin(
            key: String,
            title: String,
            isUpdate: Boolean,
            onCancel: () -> Unit,
        ): Boolean {
            // Captured BEFORE the throw: a host that registered the action and then
            // failed is the harder case, because a Cancel can already arrive.
            cancelAction = onCancel
            if (failNextBegin) {
                failNextBegin = false
                error("the host failed to open the report")
            }
            return true
        }

        override fun progress(
            key: String,
            fraction: Float,
        ) {
            progressCalls++
            onProgress?.invoke()
        }

        override fun downloading(key: String) {
            phases += "downloading"
        }

        override fun installing(key: String) {
            phases += "installing"
        }

        override fun end(key: String) {
            phases += "end"
        }
    }

    /** A connection that serves [bytes] and reports their length, nothing more. */
    private class FakeConnection(
        private val bytes: ByteArray,
    ) : HttpURLConnection(URL("https://example.invalid/plugin.jar")) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getContentLengthLong(): Long = bytes.size.toLong()

        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun downloader(reporter: TransferReporter) = TrackedDownloader(reporter)

    /** Big enough to span several 64 KiB chunks, so a mid-stream cancel is reachable. */
    private fun payload(chunks: Int) = ByteArray(64 * 1024 * chunks) { it.toByte() }

    @Test
    fun `a completed download announces downloading then installing`() {
        val reporter = FakeReporter()
        val dest = File(dir, "plugin.jar.part")

        downloader(reporter).download(FakeConnection(payload(2)), dest, "docker")
        assertTrue(reporter.progressCalls > 0, "a sized download reports a fraction")

        // Downloading is announced for EVERY attempt, so a fallback source after a
        // failed one is not streamed into a report still marked installing.
        assertEquals(listOf("downloading", "installing"), reporter.phases)
        assertTrue(dest.exists())
        assertEquals(64 * 1024 * 2, dest.length().toInt())
    }

    @Test
    fun `a cancel mid-stream stops the loop and deletes the part file`() =
        runBlocking {
            val reporter = FakeReporter()
            val dest = File(dir, "plugin.jar.part")
            // Raise the flag from inside the loop, which is what the host's Cancel does.
            reporter.onProgress = { reporter.cancelAction?.invoke() }
            val downloader = downloader(reporter)

            var thrown: Throwable? = null
            try {
                downloader.tracked("docker", "Docker", isUpdate = false) {
                    downloader.download(FakeConnection(payload(4)), dest, "docker")
                }
            } catch (e: DownloadCancelledException) {
                thrown = e
            }

            assertTrue(thrown != null, "the loop must stop rather than finish the file")
            assertEquals(DOWNLOAD_CANCELLED, thrown?.message, "the message is what silences the error banner")
            assertFalse(dest.exists(), "a part file per cancelled attempt would accumulate silently")
            assertFalse(reporter.phases.contains("installing"), "nothing was installed")
            // The report is closed even on the way out, or the row would never leave.
            assertTrue(reporter.phases.contains("end"))
        }

    @Test
    fun `the report outlives whichever operation finishes first`() =
        runBlocking {
            val reporter = FakeReporter()
            val downloader = downloader(reporter)

            // Two operations on one key are reachable - the panel and the update toast
            // can both call updatePlugin before the button goes busy. The OWNER
            // finishing first used to end the report under the joiner, leaving a live
            // download with no bar and no Cancel.
            var joinerRan = false
            downloader.tracked("docker", "Docker", isUpdate = false) {
                downloader.tracked("docker", "Docker", isUpdate = false) {
                    joinerRan = true
                }
                assertFalse(reporter.phases.contains("end"), "the joiner leaving must not close the report")
            }

            assertTrue(joinerRan)
            assertTrue(reporter.phases.contains("end"), "the last one out closes it")
        }

    @Test
    fun `a cancel raised while two operations share a key survives the first exit`() =
        runBlocking {
            val reporter = FakeReporter()
            val downloader = downloader(reporter)
            val dest = File(dir, "plugin.jar.part")

            downloader.tracked("docker", "Docker", isUpdate = false) {
                // A nested attempt exits, and the user cancels. Clearing the flag on
                // that exit discarded a cancel meant for the download still running.
                downloader.tracked("docker", "Docker", isUpdate = false) { }
                reporter.cancelAction?.invoke()

                var thrown: Throwable? = null
                try {
                    downloader.download(FakeConnection(payload(4)), dest, "docker")
                } catch (e: DownloadCancelledException) {
                    thrown = e
                }
                assertTrue(thrown != null, "the cancel must still stop the download")
            }
        }

    @Test
    fun `a flag left over from a previous transfer does not kill the next one`() =
        runBlocking {
            val reporter = FakeReporter()
            val downloader = downloader(reporter)

            // A Cancel landing after the previous transfer's finally had run used to
            // leave the key set for good, and the next install of that plugin died on
            // its first chunk with a cancel nobody asked for.
            downloader.tracked("docker", "Docker", isUpdate = false) { }
            reporter.cancelAction?.invoke()

            val dest = File(dir, "plugin.jar.part")
            downloader.tracked("docker", "Docker", isUpdate = false) {
                downloader.download(FakeConnection(payload(1)), dest, "docker")
            }

            assertTrue(dest.exists(), "the flag must be cleared when a transfer starts, not only when it ends")
        }

    @Test
    fun `a begin that throws still gives the refcount back`() =
        runBlocking {
            val reporter = FakeReporter()
            val downloader = downloader(reporter)

            reporter.failNextBegin = true
            val thrown =
                runCatching {
                    downloader.tracked("docker", "Docker", isUpdate = false) { }
                }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException, "the failure must surface, not be swallowed")

            // `begin` used to be called OUTSIDE the try, so a throw from it skipped the
            // finally entirely: the report was never closed and the key never came back
            // down. Nothing announced either - the button just stayed busy.
            assertEquals(listOf("end"), reporter.phases, "the finally must run even when begin failed")

            // And the key really is back to zero, not merely reported as such: a Cancel
            // landing after that failed attempt has to be cleared by the next transfer,
            // which only happens on the 0 -> 1 transition.
            reporter.cancelAction?.invoke()
            val dest = File(dir, "plugin.jar.part")
            downloader.tracked("docker", "Docker", isUpdate = false) {
                downloader.download(FakeConnection(payload(1)), dest, "docker")
            }
            assertTrue(dest.exists(), "a stranded refcount would leave the next install dying on its first chunk")
        }
}
