package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class PluginUpdateLeaseCleanupTest {
    private class Channel(private val failure: Throwable? = null) : FileChannel() {
        var closes = 0
        override fun implCloseChannel() { closes++; failure?.let { throw it } }
        override fun read(dst: ByteBuffer): Int = error("unused")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun read(dst: ByteBuffer, position: Long): Int = error("unused")
        override fun write(src: ByteBuffer): Int = error("unused")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun write(src: ByteBuffer, position: Long): Int = error("unused")
        override fun position(): Long = error("unused")
        override fun position(newPosition: Long): FileChannel = error("unused")
        override fun size(): Long = error("unused")
        override fun truncate(size: Long): FileChannel = error("unused")
        override fun force(metaData: Boolean) = error("unused")
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = error("unused")
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = error("unused")
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = error("unused")
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = error("unused")
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = error("unused")
    }

    private class Lock(channel: FileChannel, private val failure: Throwable? = null) :
        FileLock(channel, 0, Long.MAX_VALUE, false) {
        var releases = 0
        override fun isValid(): Boolean = channel().isOpen
        override fun release() { releases++; failure?.let { throw it } }
    }

    @Test
    fun `ordinary release and close failures preserve a completed result and clear a closed gate`() {
        val channel = Channel(IOException("private close details"))
        val lock = Lock(channel, IOException("private release details"))
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        owners["fixture"] = token
        val result = PluginUpdateLease(channel, lock, owners, "fixture", token).use { "committed" }
        assertEquals("committed", result)
        assertEquals(1, lock.releases)
        assertEquals(1, channel.closes)
        assertFalse(channel.isOpen)
        assertFalse(owners.containsKey("fixture"))
    }

    @Test
    fun `fatal release errors propagate after channel cleanup without self suppression`() {
        val fatal = AssertionError("fatal fixture")
        val channel = Channel(fatal)
        val lock = Lock(channel, fatal)
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        owners["fixture"] = token
        val caught = assertFailsWith<AssertionError> { PluginUpdateLease(channel, lock, owners, "fixture", token).close() }
        assertSame(fatal, caught)
        assertEquals(1, channel.closes)
        assertEquals(0, fatal.suppressed.size)
        assertFalse(owners.containsKey("fixture"))
    }

    @Test
    fun `fatal close errors propagate without becoming ordinary cleanup diagnostics`() {
        val fatal = AssertionError("fatal close fixture")
        val channel = Channel(fatal)
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        owners["fixture"] = token
        assertSame(fatal, assertFailsWith<AssertionError> {
            PluginUpdateLease(channel, Lock(channel), owners, "fixture", token).close()
        })
        assertFalse(owners.containsKey("fixture"))
    }

    @Test
    fun `repeated close skips an invalid lock and preserves a replacement owner`() {
        val channel = Channel()
        val lock = Lock(channel)
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        owners["fixture"] = token
        val lease = PluginUpdateLease(channel, lock, owners, "fixture", token)
        lease.close()
        val replacement = Any()
        owners["fixture"] = replacement
        lease.close()
        assertEquals(1, lock.releases)
        assertEquals(1, channel.closes)
        assertSame(replacement, owners["fixture"])
    }

    @Test
    fun `fatal diagnostic failures retain the original ordinary cleanup failure`() {
        val original = IOException("private cleanup details")
        val fatal = AssertionError("fatal logging fixture")
        assertSame(fatal, PluginUpdateLease.logCleanup("release", original) { throw fatal })
        assertEquals(listOf(original), fatal.suppressed.toList())
    }

    @Test
    fun `fatal channel cleanup wins an ordinary release failure and retains its context`() {
        val original = IOException("private release details")
        val fatal = AssertionError("fatal close fixture")
        val channel = Channel(fatal)
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        owners["fixture"] = token
        assertSame(fatal, assertFailsWith<AssertionError> {
            PluginUpdateLease(channel, Lock(channel, original), owners, "fixture", token).close()
        })
        assertEquals(listOf(original), fatal.suppressed.toList())
        assertFalse(owners.containsKey("fixture"))
    }
}
