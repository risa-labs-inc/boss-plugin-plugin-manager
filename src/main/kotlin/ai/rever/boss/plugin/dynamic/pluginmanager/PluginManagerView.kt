package ai.rever.boss.plugin.dynamic.pluginmanager

import ai.rever.boss.plugin.api.PluginContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Plugin Manager view displaying installed plugins and store.
 *
 * ## Current Implementation
 * Phase 1: Shows a placeholder UI indicating the plugin manager
 * is loaded from a bundled plugin. The actual implementation
 * will be added when PluginManagerDataProvider is available.
 *
 * ## Future Enhancement
 * When PluginManagerDataProvider is added:
 * - Show list of installed plugins with version/status
 * - Show plugin store with searchable plugin catalog
 * - Allow installing, updating, and uninstalling plugins
 * - Show plugin details with screenshots and reviews
 */
@Composable
fun PluginManagerView(context: PluginContext) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = "Plugin Manager",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Plugin Manager",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.onBackground
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Placeholder content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Info",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colors.primary.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Plugin Manager (Bundled Plugin)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "This is a core bundled plugin.\nPlugin management UI will be available\nwhen PluginManagerDataProvider is implemented.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Plugin info
        Text(
            text = "Version: 1.0.0",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
        )

        Text(
            text = "System Plugin (loadPriority: 5)",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f)
        )
    }
}
