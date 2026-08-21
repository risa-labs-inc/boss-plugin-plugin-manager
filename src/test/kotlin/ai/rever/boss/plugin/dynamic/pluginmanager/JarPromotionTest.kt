package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.impl.promoteJar
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Putting a verified download in place of the installed JAR.
 *
 * The swap this replaces was `destination.delete()` then `staged.renameTo(destination)`, which has
 * two faults that only show up when they bite:
 *
 * - **A window with no JAR at the path.** These two call sites serve SYSTEM plugins, where the
 *   path is what a part of the app itself loads from. A crash or a failed rename between the two
 *   calls leaves nothing there, and nothing to fall back to - the verified replacement is still
 *   under a `.update` name the host's directory scan deliberately skips.
 * - **A silently ignored return value.** `renameTo` answers with a Boolean, and it was discarded.
 *   A failed swap therefore reported success while leaving the OLD version installed and the new
 *   one orphaned.
 *
 * This is the path that updated terminal-tab 2.5.55 to 2.5.58 in a live install, so it is not a
 * hypothetical corner.
 */
class JarPromotionTest {
    private val dir = createTempDirectory("promote").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(
        name: String,
        content: String,
    ) = File(dir, name).apply { writeText(content) }

    @Test
    fun `a staged jar replaces the installed one`() {
        val staged = file("plugin.jar.update", "new bytes")
        val installed = file("plugin.jar", "old bytes")

        assertTrue(promoteJar(staged, installed))

        assertEquals("new bytes", installed.readText())
        assertFalse(staged.exists(), "the staged file was left behind as well as promoted")
    }

    @Test
    fun `it works when nothing is installed yet`() {
        // Reachable on the GitHub fallback path, where the target may never have existed.
        val staged = file("plugin.jar.update", "new bytes")
        val destination = File(dir, "plugin.jar")

        assertTrue(promoteJar(staged, destination))
        assertEquals("new bytes", destination.readText())
    }

    @Test
    fun `a failed promotion is reported, not swallowed`() {
        // The old code discarded `renameTo`'s Boolean, so a failure here reported success while
        // leaving the old version installed and the new one orphaned. A destination whose parent
        // does not exist is the realistic shape: the plugins directory removed under a running
        // update. (A directory as the SOURCE is not a usable case - macOS moves it happily.)
        val staged = file("plugin.jar.update", "new bytes")
        val unreachable = File(File(dir, "gone"), "plugin.jar")

        assertFalse(promoteJar(staged, unreachable))
        assertTrue(staged.exists(), "a failed promotion consumed the staged download")
    }

    @Test
    fun `a failed promotion leaves the installed version intact`() {
        // The whole point. Whatever happens, the plugin that was working keeps working.
        val installed = file("plugin.jar", "old bytes")
        val missing = File(dir, "never-downloaded.jar.update")

        assertFalse(promoteJar(missing, installed))
        assertTrue(installed.exists(), "a failed promotion destroyed the installed JAR")
        assertEquals("old bytes", installed.readText())
    }

    @Test
    fun `promoting onto the same path is not destructive`() {
        // A same-version reinstall resolves the staged and installed names to siblings, but a
        // caller that passed one path twice must not end up with the file deleted.
        val same = file("plugin.jar", "bytes")
        promoteJar(same, same)
        assertTrue(same.exists(), "promoting a file onto itself deleted it")
        assertEquals("bytes", same.readText())
    }

    @Test
    fun `the destination holds the staged bytes exactly`() {
        // A move, not a truncate-and-copy: a partially written destination is a JAR that fails to
        // load with a signature that verifies against nothing.
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val staged = File(dir, "plugin.jar.update").apply { writeBytes(payload) }
        val installed = file("plugin.jar", "old")

        assertTrue(promoteJar(staged, installed))
        assertTrue(installed.readBytes().contentEquals(payload))
    }
}
