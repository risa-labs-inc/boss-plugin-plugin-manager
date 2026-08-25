package com.risaboss.toolbox.downloadcenter

import kotlinx.coroutines.flow.StateFlow

/**
 * Where this plugin reports what it is downloading, in its own vocabulary.
 *
 * Deliberately mentions no `ai.rever.boss.plugin.api` type. Two implementations
 * sit behind it: [HostDownloadCenter]'s, which forwards to the host's download
 * center, and the plugin's own tracker feeding the status-bar widget on a host
 * that has no center. Everything in `ai.rever.boss.plugin.dynamic.pluginmanager`
 * talks to this and never to the api types, which is what lets one build run on
 * both - see [HostDownloadCenter] for why that separation has to be a package
 * boundary rather than a null check.
 */
interface TransferReporter {
    /**
     * Plugin ids with a transfer in flight, whoever started it.
     *
     * Host-wide where the host has a center, so a button reads busy for an
     * install this panel did not start; just this plugin's own work otherwise.
     */
    val busyIds: StateFlow<Set<String>>

    /**
     * Start reporting [key], or join the report already in flight for it.
     *
     * @return true when this call created the entry, so only its owner ends it.
     *   A nested fallback path gets false and must not [end] a report its caller
     *   is still filling.
     */
    fun begin(
        key: String,
        title: String,
        isUpdate: Boolean,
        onCancel: () -> Unit,
    ): Boolean

    /** Report a download fraction in 0..1. */
    fun progress(
        key: String,
        fraction: Float,
    )

    /** Bytes are arriving again - a second attempt after a failed source. */
    fun downloading(key: String)

    /** The bytes are in; what follows cannot be abandoned safely. */
    fun installing(key: String)

    /** Stop reporting [key]. Idempotent, so it is safe in a `finally`. */
    fun end(key: String)
}
