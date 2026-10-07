package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.UninstallResult

// Prefixes below are informational-style sentinels; update producers and classifier together.
internal const val INSTALL_BUSY_GUIDANCE =
    "Wait for any active installation; if it stays busy, restart BOSS and retry."
internal const val INSTALL_BUSY_NOTICE = "This plugin is busy. " + INSTALL_BUSY_GUIDANCE
internal const val INSTALL_COMPETING_PREFIX = "Busy plugins: "
internal const val ALREADY_CURRENT_PREFIX = "Plugin is already up to date (v"
internal const val ALREADY_INSTALLED_PREFIX = "Plugin already installed (v"

internal fun competingInstallNotice(names: String): String =
    INSTALL_COMPETING_PREFIX + names + ". " + INSTALL_BUSY_GUIDANCE

internal fun uninstallFailureNotice(result: UninstallResult.Failed): String =
    if (result.wasBusy()) INSTALL_BUSY_NOTICE else "Uninstall failed: ${result.error}"

/** Benign outcomes share existing message surfaces, with informational styling. */
internal fun isNeutralInstallNotice(message: String): Boolean =
    message == INSTALL_BUSY_NOTICE || message.startsWith(INSTALL_COMPETING_PREFIX) ||
        message.startsWith(ALREADY_CURRENT_PREFIX) || message.startsWith(ALREADY_INSTALLED_PREFIX)
