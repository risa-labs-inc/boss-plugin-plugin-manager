package ai.rever.boss.plugin.dynamic.pluginmanager

internal const val INSTALL_BUSY_NOTICE = "Another installation is in progress; try again when it finishes."
internal const val INSTALL_COMPETING_PREFIX = "Already being installed or updated: "
internal const val ALREADY_CURRENT_PREFIX = "Plugin is already up to date (v"
internal const val ALREADY_INSTALLED_PREFIX = "Plugin already installed (v"

/** Benign outcomes share existing message surfaces, with informational styling. */
internal fun isNeutralInstallNotice(message: String): Boolean =
    message == INSTALL_BUSY_NOTICE || message.startsWith(INSTALL_COMPETING_PREFIX) ||
        message.startsWith(ALREADY_CURRENT_PREFIX) || message.startsWith(ALREADY_INSTALLED_PREFIX)
