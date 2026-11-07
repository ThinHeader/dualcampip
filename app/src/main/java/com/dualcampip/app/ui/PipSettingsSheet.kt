package com.dualcampip.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dualcampip.app.R
import com.dualcampip.app.model.PipConfig
import com.dualcampip.app.model.PipCorner
import com.dualcampip.app.ui.theme.Chip
import com.dualcampip.app.ui.theme.Ink
import com.dualcampip.app.ui.theme.Line
import com.dualcampip.app.ui.theme.Muted
import com.dualcampip.app.ui.theme.Paper
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipSettingsSheet(
    config: PipConfig,
    locked: Boolean,
    onDismiss: () -> Unit,
    onChange: (PipConfig) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Chip,
        contentColor = Paper,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = stringResource(R.string.settings), style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.pip_corner), color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerButton(R.string.corner_top_start, config.corner == PipCorner.TOP_START, locked) {
                    onChange(config.copy(corner = PipCorner.TOP_START))
                }
                CornerButton(R.string.corner_top_end, config.corner == PipCorner.TOP_END, locked) {
                    onChange(config.copy(corner = PipCorner.TOP_END))
                }
                CornerButton(R.string.corner_bottom_start, config.corner == PipCorner.BOTTOM_START, locked) {
                    onChange(config.copy(corner = PipCorner.BOTTOM_START))
                }
                CornerButton(R.string.corner_bottom_end, config.corner == PipCorner.BOTTOM_END, locked) {
                    onChange(config.copy(corner = PipCorner.BOTTOM_END))
                }
            }
            Text(
                text = stringResource(R.string.pip_size_value, (config.widthFraction * 100).roundToInt()),
                color = Muted,
            )
            Slider(
                value = config.widthFraction,
                onValueChange = { onChange(config.copy(widthFraction = it)) },
                valueRange = PipConfig.MIN_WIDTH_FRACTION..PipConfig.MAX_WIDTH_FRACTION,
                enabled = !locked,
            )
            Text(
                text = stringResource(R.string.pip_radius_value, (config.radiusFraction * 100).roundToInt()),
                color = Muted,
            )
            Slider(
                value = config.radiusFraction,
                onValueChange = { onChange(config.copy(radiusFraction = it)) },
                valueRange = 0f..1f,
                enabled = !locked,
            )
            Text(
                text = if (locked) stringResource(R.string.pip_locked) else stringResource(R.string.privacy_note),
                color = Muted,
            )
        }
    }
}

@Composable
private fun CornerButton(
    label: Int,
    selected: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = stringResource(label),
        color = if (selected) Ink else Paper,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Paper else Line)
            .clickable(enabled = !locked, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
