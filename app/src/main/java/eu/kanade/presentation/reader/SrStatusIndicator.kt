package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.core.superresolution.SRIndicatorDisplayMode
import mihon.core.superresolution.SRIndicatorPosition
import mihon.core.superresolution.SRStatus
import mihon.core.superresolution.SRStatusInfo

@Composable
fun SRStatusIndicator(
    statusInfo: SRStatusInfo,
    position: SRIndicatorPosition,
    displayMode: SRIndicatorDisplayMode,
    modifier: Modifier = Modifier,
) {
    if (displayMode == SRIndicatorDisplayMode.HIDDEN) return

    val color = when (statusInfo.status) {
        SRStatus.PROCESSING -> Color(0xFFFF4444)
        SRStatus.DONE -> Color(0xFF4CAF50)
        SRStatus.IDLE -> Color(0xFF999999)
    }

    val icon = when (statusInfo.status) {
        SRStatus.PROCESSING -> Icons.Default.Autorenew
        SRStatus.DONE -> Icons.Default.CheckCircle
        SRStatus.IDLE -> Icons.Default.Image
    }

    val alignment = position.toAlignment()

    val paddingModifier = when (position) {
        SRIndicatorPosition.TOP_LEFT -> modifier.padding(start = 16.dp, top = 48.dp)
        SRIndicatorPosition.TOP_CENTER -> modifier.padding(top = 48.dp)
        SRIndicatorPosition.TOP_RIGHT -> modifier.padding(end = 16.dp, top = 48.dp)
        SRIndicatorPosition.BOTTOM_LEFT -> modifier.padding(start = 16.dp, bottom = 64.dp)
        SRIndicatorPosition.BOTTOM_CENTER -> modifier.padding(bottom = 64.dp)
        SRIndicatorPosition.BOTTOM_RIGHT -> modifier.padding(end = 16.dp, bottom = 64.dp)
    }

    Box(
        contentAlignment = alignment,
        modifier = paddingModifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            if (displayMode == SRIndicatorDisplayMode.ICON_AND_TEXT) {
                val labelText = when (statusInfo.status) {
                    SRStatus.PROCESSING -> {
                        val seconds = statusInfo.elapsedMs?.let { " %.1fs".format(it / 1000.0) } ?: "SR"
                        seconds
                    }
                    SRStatus.DONE -> {
                        val seconds = statusInfo.elapsedMs?.let { " %.1fs".format(it / 1000.0) } ?: "SR"
                        seconds
                    }
                    SRStatus.IDLE -> "SR"
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = labelText,
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
