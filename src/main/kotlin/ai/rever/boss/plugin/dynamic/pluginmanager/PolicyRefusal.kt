package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult

/** Preserve the exported DownloadFailed constructor while marking deterministic policy refusals.
 * The mark belongs to this result instance; an unrelated failure with identical text stays failed.
 */
internal fun policyRefusal(reason: String): InstallResult.DownloadFailed =
    InstallResult.DownloadFailed(reason).also { it.policyRefused = true }

internal fun InstallResult.wasRefused(): Boolean =
    (this as? InstallResult.DownloadFailed)?.policyRefused == true
