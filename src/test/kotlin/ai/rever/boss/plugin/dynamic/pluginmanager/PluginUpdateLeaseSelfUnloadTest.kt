package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.Closeable
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Normal cleanup after the plugin JAR closes; not an entire host self-update simulation. */
class PluginUpdateLeaseSelfUnloadTest {
    @Test
    fun `normal lease cleanup survives closing its defining URL classloader`() {
        val productionType = PluginUpdateLease::class.java
        val prefix = productionType.name
        val location = productionType.protectionDomain.codeSource.location
        val loader = object : URLClassLoader(arrayOf(location), productionType.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith(prefix)) return super.loadClass(name, resolve)
                return synchronized(getClassLoadingLock(name)) {
                    (findLoadedClass(name) ?: findClass(name)).also {
                        if (resolve) resolveClass(it)
                    }
                }
            }
        }
        val directory = Files.createTempDirectory("lease-self-unload")
        val path = directory.resolve("fixture.lock")
        val owners = ConcurrentHashMap<String, Any>()
        val token = Any()
        val ownerPath = path.toFile().canonicalPath
        owners[ownerPath] = token
        val channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            val lock = channel.lock()
            val childType = loader.loadClass(prefix)
            assertSame(loader, childType.classLoader)
            val constructor = childType.getDeclaredConstructor(
                FileChannel::class.java, FileLock::class.java, ConcurrentHashMap::class.java,
                String::class.java, Any::class.java,
            )
            constructor.isAccessible = true
            val lease = constructor.newInstance(channel, lock, owners, ownerPath, token) as Closeable
            val helper = childType.getDeclaredField("channelClose").also { it.isAccessible = true }.get(lease)
            assertSame(loader, helper.javaClass.classLoader, "Production cleanup helper must belong to the child")
            assertSame(token, owners[ownerPath])
            loader.close()
            // No manual preload of close(), its companion methods, or cleanup-only classes.
            lease.close()
            assertFalse(lock.isValid)
            assertFalse(channel.isOpen)
            assertTrue(owners.isEmpty(), "The exact owner token must be removed after successful close")
        } finally {
            loader.close()
            channel.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
