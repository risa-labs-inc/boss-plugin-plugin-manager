package ai.rever.boss.plugin.dynamic.pluginmanager

import com.risaboss.toolbox.downloadcenter.TransferReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What this plugin reports into on a host with no download center: its own
 * tracker, rendered by [DownloadStatusBarItem].
 *
 * This is the path every host before 9.4.35 takes, and it is the behaviour those
 * users already have - a progress bar in the status bar, with no dialog and no
 * Cancel behind it, because there is no host surface to put them on. Keeping it
 * is the difference between "this build shows less on an older host" and "this
 * build shows nothing there".
 *
 * [busyIds] therefore covers only this plugin's own work. Nothing else can report
 * into this tracker, which is exactly the gap the host center closes.
 */
class LocalTransferReporter(
    private val tracker: DownloadProgressTracker,
    scope: CoroutineScope,
) : TransferReporter {
    override val busyIds: StateFlow<Set<String>> =
        tracker.downloads
            .map { it.keys }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override fun begin(
        key: String,
        title: String,
        isUpdate: Boolean,
        onCancel: () -> Unit,
    ): Boolean =
        // onCancel is dropped, not stored: the widget is a bar with no click
        // target, so there is nothing here that could invoke it. A caller's
        // cancellation path simply never fires on this host.
        tracker.begin(key, title, if (isUpdate) DownloadKind.UPDATE else DownloadKind.INSTALL)

    override fun progress(
        key: String,
        fraction: Float,
    ) = tracker.progress(key, fraction)

    // The widget shows a bar and a verb, not a phase, so both are no-ops rather
    // than state nobody renders.
    override fun downloading(key: String) = Unit

    override fun installing(key: String) = Unit

    override fun end(key: String) = tracker.end(key)
}
