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
import java.util.concurrent.ConcurrentHashMap

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
internal class PluginUpdateLease internal constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val processOwners: ConcurrentHashMap<String, Any>,
    private val ownerPath: String,
    private val ownerToken: Any,
) : Closeable {
    @Synchronized
    override fun close() {
        var fatal: Error? = null
        try {
            lock.release()
        } catch (failure: Exception) {
            fatal = logCleanup("release", failure)
        } catch (failure: Error) {
            fatal = failure
        } finally {
            try {
                fatal = closeChannel(channel, fatal) as? Error
            } finally {
                // A failed close that leaves the descriptor open must keep the gate.
                if (!channel.isOpen) processOwners.remove(ownerPath, ownerToken)
            }
        }
        fatal?.let { throw it }
    }

    companion object {
        private const val PROCESS_OWNERS_KEY = "boss.plugins.updateLease.processOwners"

        @Suppress("UNCHECKED_CAST")
        private fun processOwners(): ConcurrentHashMap<String, Any> {
            val properties = System.getProperties()
            return synchronized(properties) {
                val existing = properties[PROCESS_OWNERS_KEY]
                if (existing == null) {
                    ConcurrentHashMap<String, Any>().also { properties[PROCESS_OWNERS_KEY] = it }
                } else {
                    check(existing is ConcurrentHashMap<*, *>) { "Invalid plugin update process gate" }
                    existing as ConcurrentHashMap<String, Any>
                }
            }
        }

        fun acquire(pluginDir: File, pluginId: String): Result<PluginUpdateLease> = try {
            val directory = File(pluginDir, ".plugin-update-locks")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create plugin update lock directory" }
            val name = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val lockFile = File(directory, "$name.lock").canonicalFile
            val ownerPath = lockFile.path
            val owners = processOwners()
            val token = Any() // java.lang.Object; never retain a plugin/host classloader in the shared map.
            if (owners.putIfAbsent(ownerPath, token) != null) throw PluginUpdateLeaseBusyException(pluginId)

            var channel: FileChannel? = null
            try {
                channel = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    ?: throw PluginUpdateLeaseBusyException(pluginId)
                Result.success(PluginUpdateLease(channel, lock, owners, ownerPath, token))
            } catch (failure: Throwable) {
                try {
                    throw if (channel != null) closeChannel(channel, failure) ?: failure else failure
                } finally {
                    if (channel == null || !channel.isOpen) owners.remove(ownerPath, token)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

        /** Ordinary cleanup errors cannot turn a completed installation into a failure. */
        private fun closeChannel(channel: FileChannel, previous: Throwable?): Throwable? {
            val fatal = try {
                channel.close()
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

        private fun logCleanup(phase: String, failure: Exception): Error? = try {
            System.err.println("[PluginManager] Plugin update lease cleanup failed: $phase (${failure.javaClass.simpleName})")
            null
        } catch (_: Exception) {
            null
        } catch (fatal: Error) {
            fatal
        }
    }
}
