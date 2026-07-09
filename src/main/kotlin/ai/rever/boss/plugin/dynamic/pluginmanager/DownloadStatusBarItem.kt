package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.StatusBarAlignment
import ai.rever.boss.plugin.api.StatusBarItemProvider
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bottom status-bar widget showing live plugin download/update progress.
 * Renders nothing while no download is in flight, so the bar stays clean.
 */
class DownloadStatusBarItem(
    private val tracker: DownloadProgressTracker
) : StatusBarItemProvider {
    override val itemId: String = ITEM_ID
    override val alignment: StatusBarAlignment = StatusBarAlignment.RIGHT
    override val order: Int = 10

    @Composable
    override fun Content() {
        val downloads by tracker.downloads.collectAsState()
        if (downloads.isEmpty()) return
        val items = downloads.values.toList()

        val verb = if (items.all { it.kind == DownloadKind.UPDATE }) "Updating" else "Installing"
        val label = if (items.size == 1) "$verb ${items.first().displayName}"
        else "$verb ${items.size} plugins"
        // Determinate only when every in-flight download knows its size.
        val fractions = items.map { it.progress }
        val overall = if (fractions.none { it == null }) {
            fractions.filterNotNull().average().toFloat()
        } else null

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = overall?.let { "$label ${(it * 100).toInt()}%" } ?: "$label…",
                fontSize = 11.sp,
                color = BossThemeColors.TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            val barModifier = Modifier
                .width(72.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
            if (overall != null) {
                LinearProgressIndicator(
                    progress = overall,
                    modifier = barModifier,
                    color = BossThemeColors.AccentColor,
                    backgroundColor = BossThemeColors.AccentColor.copy(alpha = 0.2f)
                )
            } else {
                LinearProgressIndicator(
                    modifier = barModifier,
                    color = BossThemeColors.AccentColor,
                    backgroundColor = BossThemeColors.AccentColor.copy(alpha = 0.2f)
                )
            }
        }
    }

    companion object {
        const val ITEM_ID = "${PluginManagerCore.PLUGIN_ID}:download-progress"
    }
}
