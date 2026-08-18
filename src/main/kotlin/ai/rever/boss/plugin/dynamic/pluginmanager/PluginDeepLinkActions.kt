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
) : DeepLinkActionHandler {

    override fun handle(action: String, params: Map<String, String>): Boolean {
        // Deep links are external input, so every parameter is validated here rather than trusted.
        val pluginId = params["plugin"]?.trim().orEmpty()
        if (!isPlausiblePluginId(pluginId)) return false

        return when (action.lowercase()) {
            "install" -> {
                offerInstall(pluginId)
                true
            }

            "open" -> {
                if (api.isPluginInstalled(pluginId)) {
                    // Reveal returns false when this host has no route that works without a
                    // window, which is every host before openPanelAsTab. Saying so beats a press
                    // that appears to do nothing.
                    if (revealPlugin?.invoke(pluginId) != true) {
                        toast("It is installed. Open it from the Toolbox.", NotificationType.INFO)
                    }
                } else {
                    // Asked to open something that is not here. Offering to install it is the
                    // useful answer, and it is the same one press either way.
                    offerInstall(pluginId)
                }
                true
            }

            else -> false
        }
    }

    /**
     * Ask, then install on the press.
     *
     * Ordering matters: already-installed is answered first so a stale page cannot produce an
     * install prompt for something the user already has, and the store lookup happens before the
     * user is asked so the question names a real plugin.
     */
    private fun offerInstall(pluginId: String) {
        if (api.isPluginInstalled(pluginId)) {
            notifications?.showToast(
                message = "It is already installed.",
                title = pluginId.substringAfterLast('.'),
                type = NotificationType.INFO,
                duration = NotificationDuration.SHORT,
                actionLabel = if (revealPlugin != null) "Open" else null,
                onAction = revealPlugin?.let { reveal -> { reveal(pluginId); Unit } },
            )
            return
        }

        scope.launch {
            val details = api.fetchPluginDetails(pluginId).getOrNull()
            if (details == null) {
                // Refused before asking anything. An id the store does not have is either a typo or
                // a link inventing one, and neither deserves an Install button.
                toast("No plugin with that id is in the store.", NotificationType.ERROR)
                return@launch
            }

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
                        // Every arm is reported. An install that quietly did nothing is the
                        // failure mode a toast-driven flow is most prone to, because there is no
                        // panel on screen to show a result in.
                        when (val result = api.installPlugin(pluginId)) {
                            is InstallResult.Success ->
                                toast("${details.displayName} installed.", NotificationType.SUCCESS)

                            is InstallResult.AlreadyInstalled ->
                                toast(
                                    "${details.displayName} is already installed (${result.currentVersion}).",
                                    NotificationType.INFO,
                                )

                            is InstallResult.DownloadFailed ->
                                toast("Download failed: ${result.error}", NotificationType.ERROR)

                            is InstallResult.LoadFailed ->
                                toast("Could not load it: ${result.error}", NotificationType.ERROR)

                            is InstallResult.VersionConflict ->
                                toast(
                                    "${details.displayName} needs ${result.required}; this BOSS is ${result.available}.",
                                    NotificationType.ERROR,
                                )
                        }
                    }
                },
            )
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
