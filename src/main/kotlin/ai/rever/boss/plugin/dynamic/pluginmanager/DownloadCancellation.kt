package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult

/**
 * What a cancelled download reports.
 *
 * The bottom-bar dialog's Cancel has to reach a blocking read loop, and the two
 * obvious ways to stop one are both wrong here:
 *
 * - Cancelling the coroutine does not interrupt `InputStream.read`, so the file
 *   keeps downloading; and when the cancellation does surface, it surfaces as a
 *   `CancellationException` through call sites that clear their busy flag AFTER
 *   the call - leaving a button spinning forever on a plugin nothing is doing.
 * - Closing the connection from another thread throws something indistinguishable
 *   from a network failure, which would be reported as one.
 *
 * So a cancel is a flag the read loop checks, and it comes back as an ordinary
 * failed install carrying [DOWNLOAD_CANCELLED]. Every button already handles a
 * failed install; [outcomeErrorFor] recognises this one and shows no error,
 * because the user asking for it to stop is an answer, not a fault.
 */
const val DOWNLOAD_CANCELLED = "Download cancelled"

/** Thrown out of the read loop when the user cancels the transfer. */
class DownloadCancelledException : Exception(DOWNLOAD_CANCELLED)

/**
 * Whether this outcome is the user stopping the transfer rather than a fault.
 *
 * Next to the constant, and public to the package, because three places have to
 * agree: the store path that must not fall through to GitHub after a cancel, the
 * buttons' error text, and the update toast. The toast was the one that did not,
 * and it is the path where the host dialog's Cancel is actually reachable.
 */
internal fun InstallResult.wasCancelled(): Boolean = this is InstallResult.DownloadFailed && error == DOWNLOAD_CANCELLED
