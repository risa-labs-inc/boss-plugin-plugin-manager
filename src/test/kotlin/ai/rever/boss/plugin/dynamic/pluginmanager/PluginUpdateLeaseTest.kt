package ai.rever.boss.plugin.dynamic.pluginmanager

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PluginUpdateLeaseTest {
    @Test
    fun `concurrent first leases for different plugins share a newly created lock directory`() {
        val directory = Files.createTempDirectory("plugin-update-lease-mkdir").toFile()
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val results = (0 until 8).map { index ->
                executor.submit(Callable {
                    ready.countDown()
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Lease start barrier timed out")
                    PluginUpdateLease.acquire(directory, "plugin-$index").getOrThrow().use { }
                })
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Lease workers did not reach the barrier")
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            val lockFiles = directory.resolve(".plugin-update-locks").listFiles()!!
            assertEquals(8, lockFiles.size)
            val owners = PluginUpdateProcessRegistry.owners()
            lockFiles.forEach { assertFalse(owners.containsKey(PluginUpdateProcessRegistry.ownerKey(it.canonicalFile.path))) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Lease workers did not finish")
            directory.deleteRecursively()
        }
    }

    @Test
    fun `separate installers contend for the same disk lease and exceptions release it`() {
        val directory = Files.createTempDirectory("plugin-update-lease").toFile()
        try {
            assertFailsWith<IllegalStateException> {
                PluginUpdateLease.acquire(directory, "plugin").getOrThrow().use {
                    assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
                    PluginUpdateLease.acquire(directory, "other").getOrThrow().close()
                    error("Installer failed")
                }
            }
            PluginUpdateLease.acquire(directory, "plugin").getOrThrow().close()
        } finally { directory.deleteRecursively() }
    }
}
