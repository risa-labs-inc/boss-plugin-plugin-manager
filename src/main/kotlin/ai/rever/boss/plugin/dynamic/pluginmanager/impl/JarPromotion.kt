package ai.rever.boss.plugin.dynamic.pluginmanager.impl

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Move [staged] onto [destination] in one filesystem operation.
 *
 * The alternative - `destination.delete()` then `staged.renameTo(destination)` - has two faults,
 * and the update paths that used it serve SYSTEM plugins, where the destination is what a part of
 * the app itself loads from:
 *
 * - **It leaves a window with no JAR at the path.** A crash or a failed rename between the two
 *   calls leaves nothing there and nothing to fall back to, because the verified replacement is
 *   still under a `.update` name the host's directory scan deliberately skips.
 * - **It ignored the answer.** `renameTo` returns a Boolean and both call sites discarded it, so a
 *   failed swap reported success while leaving the OLD version installed and the new one orphaned.
 *
 * This is the path that took terminal-tab from 2.5.55 to 2.5.58 in a live install, so neither is a
 * hypothetical corner.
 *
 * A top-level function rather than a method on `PluginManagerAPIImpl`, because it touches nothing
 * but the filesystem and that class builds a Supabase client in its constructor - so as a member it
 * could not be reached from a test at all.
 *
 * ATOMIC_MOVE is requested but not required. It is unsupported across filesystems; the staged file
 * is always a sibling of the destination so in practice it holds, and the fallback is still a
 * single move rather than the old two-step.
 */
internal fun promoteJar(
    staged: File,
    destination: File,
): Boolean =
    runCatching {
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }.recoverCatching {
        Files.move(
            staged.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.isSuccess
