package ai.rever.boss.plugin.dynamic.pluginmanager

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUpdateLeaseProcessTest {
    @Test
    fun `same JVM contention leaves the holder locked against an external JVM`() {
        val directory = Files.createTempDirectory("plugin-lease-process").toFile()
        try {
            val probe = File(directory, "LeaseProbe.java").apply {
                writeText("""
                    import java.nio.channels.*;
                    import java.nio.file.*;
                    class LeaseProbe {
                        public static void main(String[] args) throws Exception {
                            try (FileChannel channel = FileChannel.open(Paths.get(args[0]),
                                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                                try (FileLock lock = channel.tryLock()) {
                                    System.out.println(lock == null ? "BUSY" : "ACQUIRED");
                                }
                            }
                        }
                    }
                """.trimIndent() + "\n")
            }
            val lease = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            val lockFile = File(directory, ".plugin-update-locks").listFiles()!!.single()
            try {
                assertEquals("BUSY", probe(probe, lockFile))
                assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
                assertEquals("BUSY", probe(probe, lockFile),
                    "closing a contending same-process descriptor released the holder's OS lock")
            } finally { lease.close() }
            assertEquals("ACQUIRED", probe(probe, lockFile))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `the shared process gate uses bootstrap objects and exact owner tokens`() {
        val directory = Files.createTempDirectory("plugin-lease-gate").toFile()
        try {
            val first = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            val owners = PluginUpdateProcessRegistry.owners()
            val lockFile = File(directory, ".plugin-update-locks").listFiles()!!.single().canonicalFile
            val token = owners[PluginUpdateProcessRegistry.ownerKey(lockFile.path)]!!
            assertEquals(null, owners.javaClass.classLoader)
            assertTrue(token is String)
            first.close()
            val second = PluginUpdateLease.acquire(directory, "plugin").getOrThrow()
            try {
                first.close()
                assertTrue(owners[PluginUpdateProcessRegistry.ownerKey(lockFile.path)] !== token)
                assertTrue(PluginUpdateLease.acquire(directory, "plugin").isFailure)
            } finally { second.close() }
            assertFalse(owners.containsKey(PluginUpdateProcessRegistry.ownerKey(lockFile.path)))
        } finally { directory.deleteRecursively() }
    }

    private fun probe(source: File, lockFile: File): String {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val java = File(System.getProperty("java.home"), "bin/java" + if (windows) ".exe" else "")
        val output = File(source.parentFile, "probe-output.txt")
        val process = ProcessBuilder(java.path, "--source", "17", source.path, lockFile.path)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "external JVM lock probe timed out")
            val text = output.readText().trim()
            assertEquals(0, process.exitValue(), "external JVM lock probe failed: $text")
            return text
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
