package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.UninstallResult
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Neutral contention result: another installer owns this plugin, rather than a failed transfer. */
const val UPDATE_INSTALL_BUSY = "Plugin installation already in progress"

internal fun InstallResult.wasBusy(): Boolean =
    this is InstallResult.DownloadFailed && error == UPDATE_INSTALL_BUSY

internal fun UninstallResult.wasBusy(): Boolean =
    this is UninstallResult.Failed && error == UPDATE_INSTALL_BUSY

internal class PluginUpdateLeaseBusyException(pluginId: String) :
    IllegalStateException("Another installation is already updating $pluginId")

/** Shared disk protocol with Toolbox/host; keep lock files so contenders lock the same inode. */
internal class PluginUpdateLease private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    override fun close() {
        try { lock.release() } catch (failure: Throwable) {
            closeAfterFailure(channel, failure)
            throw failure
        }
        channel.close()
    }

    companion object {
        fun acquire(pluginDir: File, pluginId: String): Result<PluginUpdateLease> = try {
            val directory = File(pluginDir, ".plugin-update-locks")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin update lock directory" }
            val name = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val channel = FileChannel.open(File(directory, "$name.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            } catch (failure: Throwable) {
                closeAfterFailure(channel, failure)
                throw failure
            }
            if (lock == null) {
                val busy = PluginUpdateLeaseBusyException(pluginId)
                closeAfterFailure(channel, busy)
                throw busy
            }
            Result.success(PluginUpdateLease(channel, lock))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        /** Cleanup cannot turn a fatal failure into an ordinary install result. */
        private fun closeAfterFailure(channel: FileChannel, failure: Throwable) {
            try { channel.close() } catch (cleanup: Throwable) {
                if (cleanup is Error && failure !is Error) {
                    cleanup.addSuppressed(failure)
                    throw cleanup
                }
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }
}
