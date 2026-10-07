package ai.rever.boss.plugin.dynamic.pluginmanager

internal const val INSTALL_BUSY_NOTICE = "Another installation is in progress; try again when it finishes."

/** Benign outcomes share existing message surfaces, with informational styling. */
internal fun isNeutralInstallNotice(message: String): Boolean =
    message == INSTALL_BUSY_NOTICE || message.startsWith("Already being installed or updated: ") ||
        message.startsWith("Plugin is already up to date (v") || message.startsWith("Plugin already installed (v")
