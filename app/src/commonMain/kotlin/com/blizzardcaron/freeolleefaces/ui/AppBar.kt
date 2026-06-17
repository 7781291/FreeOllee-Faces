package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.connectionChip

/** Top app bar: optional back affordance, Nunito-800 title, optional trailing actions.
 *  Recreates the design system navigation/AppBar.jsx. */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    connectionStatus: ConnectionStatus? = null,
    onReconnect: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 0.dp else 4.dp),
        )
        if (connectionStatus != null) {
            ConnectionChipCompact(connectionStatus, onReconnect)
        }
        actions()
    }
}

/** Compact connection indicator for the AppBar trailing area. Tappable to reconnect only when the
 *  chip is clickable (NotReachable / NoWatch). Mirrors Home's ConnectionRow look at smaller size. */
@Composable
private fun ConnectionChipCompact(status: ConnectionStatus, onReconnect: (() -> Unit)?) {
    val chip = connectionChip(status)
    val color = connectionStatusColor(status)
    if (chip.clickable && onReconnect != null) {
        TextButton(onClick = onReconnect) {
            Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chip.showSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Status→color used by both the AppBar connection chip and Home's ConnectionRow, kept in one place. */
@Composable
internal fun connectionStatusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
    ConnectionStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
    ConnectionStatus.NotReachable, ConnectionStatus.NoWatch -> MaterialTheme.colorScheme.error
}
