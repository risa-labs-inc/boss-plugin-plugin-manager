package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.UpdateInfo

/** Internal capability: an empty success is distinguishable from an unavailable store. */
internal interface CompatibleUpdateSource {
    suspend fun checkForCompatibleUpdatesResult(): Result<List<UpdateInfo>>
}
