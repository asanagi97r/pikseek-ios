package dev.piko.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform

/** 应用图标，与 [PikoBrandIcons.Logo] 是同一张。 */
val PikoLogo: ImageVector get() = PikoBrandIcons.Logo

/** 图标、应用名与版本号，放在侧边栏左上角。 */
@Composable
fun PikoBrand(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(PikoLogo, contentDescription = null, modifier = Modifier.size(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "PikSeek",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                LocalPikoPlatform.current.appVersion,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}
