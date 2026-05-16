package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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

    val displayText = when (statusInfo.status) {
        SRStatus.PROCESSING -> "\uD83D\uDD34 超分中"
        SRStatus.DONE -> "\uD83D\uDFE2 超分完成${statusInfo.elapsedMs?.let { " ${it}ms" } ?: ""}"
        SRStatus.IDLE -> ""
    }

    if (statusInfo.status == SRStatus.IDLE && displayMode != SRIndicatorDisplayMode.ICON_AND_TEXT) return

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
            Text(
                text = when (statusInfo.status) {
                    SRStatus.PROCESSING -> "\uD83D\uDD34"
                    SRStatus.DONE -> "\uD83D\uDFE2"
                    SRStatus.IDLE -> "\u26AA"
                },
                fontSize = 12.sp,
            )
            if (displayMode == SRIndicatorDisplayMode.ICON_AND_TEXT && statusInfo.status != SRStatus.IDLE) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = displayText,
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
