package ai.rever.boss.plugin.dynamic.pluginmanager

/** Optional host signals; absent properties preserve manual prompts on older hosts. */
data class HostAutomaticUpdatePolicy(val enabled: Boolean, val optOuts: Set<String>) {
    fun allowsPrompt(pluginId: String): Boolean = !enabled || pluginId in optOuts

    companion object {
        fun read(): HostAutomaticUpdatePolicy = parse(
            System.getProperty("boss.plugins.autoUpdate.enabled"),
            System.getProperty("boss.plugins.autoUpdate.optOuts"),
        )

        fun parse(enabled: String?, optOuts: String?): HostAutomaticUpdatePolicy = HostAutomaticUpdatePolicy(
            enabled.toBoolean(),
            optOuts.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}
