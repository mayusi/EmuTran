package io.github.mayusi.emutran.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Compact monochrome step indicator for the 6-step setup flow.
 *
 * Renders a row of [total] small pill/segment shapes plus an optional
 * "Step N of 6" label to the right.  State encoding:
 *   • completed (step < current) — white fill, no border.
 *   • active    (step == current) — white fill, slightly wider.
 *   • upcoming  (step > current)  — transparent fill, EmuTones.outlineRest border.
 *
 * Monochrome rule: white = done/active, gray-bordered = pending.  No hue ever.
 *
 * @param current 1-based current step number (1 … total).
 * @param total   Total number of steps in the flow. Default 6.
 * @param showLabel If true, appends a "Step N of 6" text label after the dots.
 */
@Composable
fun StepIndicator(
    current: Int,
    total: Int = 6,
    showLabel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (step in 1..total) {
            StepSegment(
                filled  = step <= current,
                active  = step == current,
            )
        }
        if (showLabel) {
            Text(
                text  = "Step $current of $total",
                style = MaterialTheme.typography.labelMedium,
                color = EmuTones.onSurfaceVar,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun StepSegment(
    filled: Boolean,
    active: Boolean,
) {
    // Active segment is a touch wider to call attention.
    val segWidth  = if (active) 20.dp else 14.dp
    val segHeight = 4.dp
    val shape     = RoundedCornerShape(2.dp)

    val bg     = if (filled) Color.White else Color.Transparent
    val border = if (!filled) Modifier.border(1.dp, EmuTones.outlineRest, shape) else Modifier

    Box(
        modifier = Modifier
            .width(segWidth)
            .height(segHeight)
            .background(bg, shape)
            .then(border),
    )
}
