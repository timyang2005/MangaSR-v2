package eu.kanade.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import mihon.core.superresolution.SRIndicatorPosition

@Composable
fun SRStatusIndicator(
    isSrProcessed: Boolean,
    position: SRIndicatorPosition,
    modifier: Modifier = Modifier,
) {
    if (!isSrProcessed) return

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
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "SR Processed",
            tint = Color(0xFF4CAF50),
            modifier = Modifier
                .size(20.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(2.dp),
        )
    }
}
