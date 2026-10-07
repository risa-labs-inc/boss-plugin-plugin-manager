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
import java.util.Properties
import java.util.UUID

/** Neutral contention result: another installer owns this plugin, rather than a failed transfer. */
internal const val UPDATE_INSTALL_BUSY = "Plugin installation already in progress"

internal fun InstallResult.wasBusy(): Boolean =
    this is InstallResult.DownloadFailed && error == UPDATE_INSTALL_BUSY

internal fun UninstallResult.wasBusy(): Boolean =
    this is UninstallResult.Failed && error == UPDATE_INSTALL_BUSY

internal class PluginUpdateLeaseBusyException(pluginId: String) :
    IllegalStateException("Another installation is already updating $pluginId")

/**
 * Shared host/Toolbox protocol. Keep disk lock files permanently and claim the
 * bootstrap-JDK process gate before opening any descriptor for that lock file.
 * POSIX can release the owner's OS lock when a contending channel is closed.
 */
internal class PluginUpdateLease private constructor(
    private val channelClose: PluginUpdateLeaseChannelClose,
    private val lock: FileLock,
    private val processOwners: Properties,
    private val ownerPath: String,
    private val ownerToken: String,
) : Closeable {
    internal constructor(
        channel: FileChannel,
        lock: FileLock,
        processOwners: Properties,
        ownerPath: String,
        ownerToken: String,
    ) : this(PluginUpdateLeaseChannelClose(channel), lock, processOwners, ownerPath, ownerToken)

    @Synchronized
    override fun close() {
        var cleanupFailure: Throwable? = null
        try {
            if (lock.isValid) lock.release()
        } catch (failure: Exception) {
            cleanupFailure = logCleanup("release", failure) ?: failure
        } catch (failure: Error) {
            cleanupFailure = failure
        } finally {
            try {
                cleanupFailure = closeChannel(channelClose, cleanupFailure)
            } finally {
                // Java marks a channel closed before native cleanup; a thrown close is uncertain.
                // Keep that uncertainty sticky even when repeated close becomes a no-op.
                if (channelClose.confirmed) processOwners.remove(ownerPath, ownerToken)
            }
        }
        (cleanupFailure as? Error)?.let { throw it }
    }

    companion object {
        fun acquire(pluginDir: File, pluginId: String): Result<PluginUpdateLease> =
            acquire(pluginDir, pluginId) { lockFile ->
                FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            }

        internal fun acquire(
            pluginDir: File,
            pluginId: String,
            openChannel: (File) -> FileChannel,
        ): Result<PluginUpdateLease> = try {
            // Resolve and allocate cleanup state before claiming ownership or opening a descriptor.
            val channelClose = PluginUpdateLeaseChannelClose(null)
            val directory = File(pluginDir, ".plugin-update-locks")
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create plugin update lock directory" }
            val name = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val lockFile = File(directory, "$name.lock").canonicalFile
            val ownerPath = PluginUpdateProcessRegistry.ownerKey(lockFile.path)
            val owners = PluginUpdateProcessRegistry.owners()
            val token = UUID.randomUUID().toString() // Only bootstrap Strings enter shared properties.
            if (owners.putIfAbsent(ownerPath, token) != null) throw PluginUpdateLeaseBusyException(pluginId)

            try {
                val channel = openChannel(lockFile)
                channelClose.attach(channel)
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw PluginUpdateLeaseBusyException(pluginId)
                Result.success(PluginUpdateLease(channelClose, lock, owners, ownerPath, token))
            } catch (failure: Throwable) {
                try {
                    throw closeChannel(channelClose, failure) ?: failure
                } finally {
                    if (channelClose.confirmed) owners.remove(ownerPath, token)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        /** Ordinary cleanup errors cannot turn a completed installation into a failure. */
        private fun closeChannel(channelClose: PluginUpdateLeaseChannelClose, previous: Throwable?): Throwable? {
            val fatal = try {
                channelClose.close()
                null
            } catch (cleanup: Exception) {
                logCleanup("close", cleanup)
            } catch (cleanup: Error) {
                cleanup
            }
            if (fatal == null) return previous
            if (previous is Error) {
                if (previous !== fatal) previous.addSuppressed(fatal)
                return previous
            }
            if (previous != null && previous !== fatal) fatal.addSuppressed(previous)
            return fatal
        }

        internal fun logCleanup(
            phase: String,
            failure: Exception,
            diagnostic: (String) -> Unit = { System.err.println(it) },
        ): Error? = try {
            diagnostic("[PluginManager] Plugin update lease cleanup failed: $phase (${failure.javaClass.simpleName})")
            null
        } catch (_: Exception) {
            null
        } catch (fatal: Error) {
            fatal.addSuppressed(failure)
            fatal
        }
    }
}

/** A failed native close is sticky even though AbstractInterruptibleChannel reports closed. */
private class PluginUpdateLeaseChannelClose(private var channel: FileChannel?) {
    var confirmed: Boolean = channel == null
        private set
    private var attempted = false

    fun attach(channel: FileChannel) {
        confirmed = false
        this.channel = channel
    }

    fun close() {
        if (attempted) return
        attempted = true
        val attached = channel
        attached?.close()
        confirmed = attached == null || !attached.isOpen
    }
}
