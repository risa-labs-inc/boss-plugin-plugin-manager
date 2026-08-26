package ai.rever.boss.plugin.dynamic.pluginmanager

import com.risaboss.toolbox.downloadcenter.TransferReporter
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * One download, reported and cancellable.
 *
 * Split out of `PluginManagerAPIImpl` because none of this needs a store client:
 * that class constructs Supabase on the way up, so the read loop and the cancel
 * flag - the trickiest new logic here - could not be reached from a test while
 * they lived inside it.
 *
 * @param reporter where progress goes: the host's download center, or this
 *   plugin's own status-bar widget on a host that has none.
 */
class TrackedDownloader(
    private val reporter: TransferReporter
) {
    /** Friendly names the API only learns from the panel that listed the store row. */
    val displayNames = DownloadDisplayNames()

    /**
     * Keys the user has asked to abandon, checked by [download] per chunk.
     *
     * A flag rather than a coroutine cancellation - see [DownloadCancelledException]
     * for why cancelling the job neither stops the blocking read nor leaves the
     * buttons in a sane state.
     */
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many operations are reporting each key, so the flag is cleared on the
     * 0 -> 1 transition only.
     *
     * The panel and the update toast can both call `updatePlugin(id)` before the
     * button goes busy. Clearing unconditionally at entry meant the second start wiped
     * a Cancel the user had just pressed on the first, and the original download ran to
     * completion with the row gone.
     */
    private val active = ConcurrentHashMap<String, Int>()

    /**
     * Report [key] while [block] runs, and let the user abandon it.
     *
     * The Cancel handed to the reporter raises a flag the read loop checks, which is
     * the only thing that stops a blocking read; the reporter withdraws the button
     * itself once the transfer says it is installing, since a jar swap cannot be
     * stopped safely.
     *
     * Nested operations on the same key join the outer report, and only the owner
     * cleans up - so a fallback path inside an install cannot close the report its
     * caller is still filling, or discard a cancel meant for it.
     */
    suspend fun <T> tracked(
        key: String,
        displayName: String,
        isUpdate: Boolean,
        block: suspend () -> T
    ): T {
        // The refcount is taken FIRST and nothing else sits outside the try, because the
        // `finally` below is the only thing that gives it back. `reporter.begin` used to
        // be out here, and it is host code - `HostCenterReporter` calls straight into
        // `DownloadCenterProvider.begin` inside a `computeIfAbsent` - so a throw from it
        // stranded this key above zero for the rest of the session. Two things then
        // stayed broken and neither announced itself: the 0 -> 1 branch never ran again,
        // so a stale cancel flag was never cleared and the NEXT install of this plugin
        // died on its first chunk with a Cancel nobody pressed; and `reporter.end` never
        // ran, so the row and the busy button outlived the download.
        val first = active.merge(key, 1, Int::plus) == 1
        try {
            // Cleared BEFORE the report opens, not only after it closes: onCancel is a
            // host callback and nothing orders it against the `finally` below, so a
            // Cancel landing in that window used to leave the key set for good and the
            // next install of this plugin died on its first chunk. Only on the first
            // entry though - a second concurrent start must not wipe a live cancel.
            if (first) cancelledTransfers.remove(key)
            // The hint is consumed only by the call that opens the report. Evaluated as
            // an argument, whichever call arrived first ate it - even when it lost
            // ownership and its title was discarded, leaving the owner's row showing the
            // raw fallback. `take` says "consumes"; this is what that has to mean.
            val owned = reporter.begin(
                key = key,
                title = displayNames.peek(key) ?: displayName,
                isUpdate = isUpdate,
                onCancel = { cancelledTransfers.add(key) }
            )
            // Consumed only by the call that opened the report.
            if (owned) displayNames.take(key, displayName)
            return block()
        } finally {
            // Only the owner cleans up. Two concurrent operations on one key are
            // reachable - the panel and the update toast can both call updatePlugin
            // before the button goes busy - and the loser clearing the flag would
            // discard a cancel the user just asked of the download still running,
            // while its `end` would close the report the winner is still filling.
            // The LAST one out closes the report, not the owner. The owner finishing
            // first while a joiner is still streaming used to end the row - leaving a
            // live download with no bar and no Cancel - and clear a cancel the user may
            // have just pressed for it. `owned` now decides only who called begin,
            // which is all it should ever have meant; `end` is keyed and idempotent, so
            // a non-owner calling it is fine.
            val remaining = active.merge(key, -1) { current, delta -> (current + delta).takeIf { it > 0 } }
            if (remaining == null) {
                cancelledTransfers.remove(key)
                reporter.end(key)
            }
        }
    }

    /**
     * Stream [connection]'s body into [dest], reporting progress to the host row
     * opened for [progressKey]. [expectedSize] (from the store's download info) is
     * the fallback when the response lacks a Content-Length.
     */
    fun download(
        connection: HttpURLConnection,
        dest: File,
        progressKey: String,
        expectedSize: Long = 0L
    ) {
        // Announced on every attempt, not once at begin: the store path can finish
        // its bytes, fail verification or a refused unload, and fall through to the
        // GitHub source - which would otherwise stream a second jar into a report
        // still marked installing, where the host offers no Cancel and may ignore
        // progress.
        reporter.downloading(progressKey)
        val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedSize
        // Deleted after the streams are closed, never while the output stream is still
        // open: on Windows the delete would simply fail.
        var cancelledDest: File? = null
        try {
            dest.outputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        if (progressKey in cancelledTransfers) {
                            // Checked per chunk, so Cancel takes effect within one buffer
                            // rather than at the next suspension point - a blocking read
                            // has none.
                            //
                            // The half-written file goes with it, here rather than in each
                            // caller's catch. Every path downloads into a sibling the
                            // directory scan ignores - `.part`, or `.jar.update` for a
                            // locked plugin - which is inert but accumulates one per
                            // cancelled attempt.
                            cancelledDest = dest
                            throw DownloadCancelledException()
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            // Throttle state updates to whole-percent steps
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                reporter.progress(progressKey, copied.toFloat() / total)
                            }
                        }
                    }
                }
            }
        } finally {
            cancelledDest?.let { runCatching { it.delete() } }
        }
        // The bytes are in; what follows is verifying and loading them. Saying so is
        // what withdraws the row's Cancel, which from here on could only leave a
        // half-swapped plugin.
        reporter.installing(progressKey)
    }
}
