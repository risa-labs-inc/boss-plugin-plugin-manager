package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.DeepLinkActionHandler
import ai.rever.boss.plugin.api.NotificationDuration
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.NotificationType
import ai.rever.boss.plugin.dynamic.pluginmanager.api.InstallResult
import ai.rever.boss.plugin.dynamic.pluginmanager.api.PluginManagerAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * `boss://plugin?id=<this plugin>&action=install|open&plugin=<plugin id>`.
 *
 * This exists so a plugin's web page can offer Open or Install. That page is served by an edge
 * function and cannot know what is installed on this machine, so the page's job is to ASK and this
 * handler's job is to decide - which also means the page can be wrong about the button's label
 * without being able to cause the wrong thing to happen.
 *
 * NOTHING INSTALLS WITHOUT A PRESS. A deep link is external input: the `boss://` scheme is
 * registered with the OS, so any page that can ask the OS to open a URL reaches this code, and the
 * host's own DeepLinkOrigin notes that a link is not evidence the operator asked for anything. So
 * `install` never installs. It shows a toast naming the plugin with an Install button on it, and
 * the install happens on that press. The worst a hostile page can do is make a toast appear.
 *
 * The name in that toast comes from the STORE, not from the link. A link could otherwise say
 * anything it liked about what you were about to install; looking the id up means the toast
 * describes the thing that will actually be fetched, and an id that is not in the store is refused
 * before the user is asked anything at all.
 *
 * Works with no panel open, which is the normal case when a link arrives from a browser: toasts are
 * the host's, and installing goes through the same API the Install button uses.
 */
class PluginDeepLinkActions(
    override val handlerId: String,
    private val api: PluginManagerAPI,
    private val notifications: NotificationProvider?,
    private val scope: CoroutineScope,
    /** Reveal an installed plugin. Null when this host cannot open panels, which is survivable. */
    private val revealPlugin: ((String) -> Boolean)?,
    /**
     * Re-read what is installed, before deciding anything.
     *
     * [PluginManagerAPI.isPluginInstalled] answers from a cached list that is filled by the panel
     * and by a startup pass. A deep link arrives from outside with no panel open and no guarantee
     * either has run, so asking the cache can answer "not installed" about a plugin that is
     * plainly installed - and the whole point of `open` is that it must not then offer to install
     * it. Refreshing first is what makes the answer about this machine rather than about whether
     * the Toolbox has been looked at yet.
     */
    private val refreshInstalled: suspend () -> Unit,
    /**
     * Ask the operator, modally, and return their answer. Null on a host with no dialog provider.
     *
     * A DIALOG rather than a toast, because this is a question whose answer installs software. A
     * toast is an announcement that happens to carry a button: it can be missed entirely, it times
     * out on its own, and dismissing it and answering "no" are the same gesture. Asking whether to
     * run somebody's code deserves a thing that waits for an answer.
     *
     * Still nullable, and the toast below is still the fallback: PluginContext providers may be
     * absent and a plugin must degrade rather than lose the feature.
     */
    private val confirmInstall: (suspend (title: String, message: String) -> Boolean)?,
) : DeepLinkActionHandler {

    override fun handle(action: String, params: Map<String, String>): Boolean {
        // Deep links are external input, so every parameter is validated here rather than trusted.
        val pluginId = params["plugin"]?.trim().orEmpty()
        if (!isPlausiblePluginId(pluginId)) return false

        val verb = action.lowercase()
        if (verb != "install" && verb != "open") return false

        // Everything happens after a refresh, so no branch below can be decided by a cache that
        // nothing has filled yet. `handle` cannot suspend, so it reports handled and gets on with
        // it; every outcome tells the user what happened.
        scope.launch {
            runCatching { refreshInstalled() }
            val installed = installedNow(pluginId)
            trace("action=$verb plugin=$pluginId installed=$installed")

            when {
                installed && verb == "open" -> {
                    // Reveal returns false when this host has no route that works without a
                    // window, which is every host before openPanelAsTab. Saying so beats a press
                    // that appears to do nothing.
                    if (revealPlugin?.invoke(pluginId) != true) {
                        toast("It is installed. Open it from the Toolbox.", NotificationType.INFO)
                    }
                }

                // Asked to INSTALL something already here. Never a reinstall: that was the bug
                // this ordering exists to prevent, and an update is a different verb with its own
                // button in the Toolbox.
                installed -> alreadyInstalled(pluginId)

                else -> offerInstall(pluginId)
            }
        }
        return true
    }

    /**
     * Whether this machine has [pluginId], compared case-insensitively.
     *
     * The id makes a round trip through a URL and a database column on its way here, and neither
     * preserves case by contract. Every id in this store is lower case, so this changes no answer
     * today; it means one that arrives shouting is still recognised rather than installed twice.
     */
    private fun installedNow(pluginId: String): Boolean =
        api.getInstalledPlugins().any { it.pluginId.equals(pluginId, ignoreCase = true) }

    /**
     * One line per deep link, to stderr.
     *
     * Plugins have no logger, and this path has no UI of its own: without this, "it installed
     * instead of opening" is a report with nothing behind it, which is exactly how this arrived.
     * A deep link is a rare, user-initiated event, so one line is not a log to be spammed.
     */
    private fun trace(message: String) {
        System.err.println("[plugin-manager] deeplink $message")
    }

    /**
     * Ask, then install on the press.
     *
     * Ordering matters: already-installed is answered first so a stale page cannot produce an
     * install prompt for something the user already has, and the store lookup happens before the
     * user is asked so the question names a real plugin.
     */
    private fun alreadyInstalled(pluginId: String) {
        notifications?.showToast(
            message = "It is already installed.",
            title = pluginId.substringAfterLast('.'),
            type = NotificationType.INFO,
            duration = NotificationDuration.SHORT,
            actionLabel = if (revealPlugin != null) "Open" else null,
            onAction = revealPlugin?.let { reveal -> { reveal(pluginId); Unit } },
        )
    }

    private fun offerInstall(pluginId: String) {
        scope.launch {
            val details = api.fetchPluginDetails(pluginId).getOrNull()
            if (details == null) {
                // Refused before asking anything. An id the store does not have is either a typo or
                // a link inventing one, and neither deserves an Install button.
                toast("No plugin with that id is in the store.", NotificationType.ERROR)
                return@launch
            }

            val title = "Install ${details.displayName}?"
            // The id is in the message deliberately. Display names are chosen by whoever published
            // the plugin and two of them can read alike; the id is what will actually be fetched.
            // version is nullable and latestVersion is the store's other name for it; without this
            // the dialog offers to install "Fake Plugin null".
            val version = (details.version ?: details.latestVersion)?.takeIf { it.isNotBlank() }
            val named = if (version == null) details.displayName else "${details.displayName} $version"
            val message =
                "$named will be downloaded from the BOSS plugin store and loaded into this app." +
                    "\n\n$pluginId"

            val ask = confirmInstall
            if (ask != null) {
                val confirmed = runCatching { ask(title, message) }.getOrDefault(false)
                trace("confirm plugin=$pluginId confirmed=$confirmed")
                if (!confirmed) return@launch
                install(pluginId, details.displayName)
                return@launch
            }

            // No dialog provider on this host. The toast keeps the feature working rather than
            // dropping it, and it is the weaker of the two on purpose.
            notifications?.showToast(
                message = "Install ${details.displayName} from the plugin store?",
                title = "Install plugin",
                type = NotificationType.INFO,
                // Long, because this asks a question: a toast that vanishes mid-read is a question
                // nobody answered.
                duration = NotificationDuration.LONG,
                actionLabel = "Install",
                onAction = {
                    scope.launch {
                        install(pluginId, details.displayName)
                    }
                },
            )
        }
    }

    /**
     * Do it, and say what happened.
     *
     * Every arm is reported. An install that quietly did nothing is the failure mode this flow is
     * most prone to: there is no panel on screen to show a result in, so silence is indistinguishable
     * from success.
     */
    private suspend fun install(pluginId: String, displayName: String) {
        when (val result = api.installPlugin(pluginId)) {
            is InstallResult.Success ->
                toast("$displayName installed.", NotificationType.SUCCESS)

            is InstallResult.AlreadyInstalled ->
                toast("$displayName is already installed (${result.currentVersion}).", NotificationType.INFO)

            is InstallResult.DownloadFailed ->
                toast("Download failed: ${result.error}", NotificationType.ERROR)

            is InstallResult.LoadFailed ->
                toast("Could not load it: ${result.error}", NotificationType.ERROR)

            is InstallResult.VersionConflict ->
                toast("$displayName needs ${result.required}; this BOSS is ${result.available}.", NotificationType.ERROR)
        }
    }

    private fun toast(message: String, type: NotificationType) {
        notifications?.showToast(
            message = message,
            title = "Plugins",
            type = type,
            duration = NotificationDuration.SHORT,
        )
    }

    companion object {
        /**
         * The shape every plugin id in this store has: dotted reverse-DNS.
         *
         * Checked because this value is built into a store request and shown to the user in a
         * prompt. It is deliberately a charset-and-shape test rather than a lookup - the lookup
         * happens next, and doing it for arbitrary strings would turn this handler into a way to
         * make the app issue requests of somebody else's choosing.
         */
        private val PLUGIN_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")

        fun isPlausiblePluginId(value: String): Boolean = PLUGIN_ID.matches(value)
    }
}
