package io.github.mayusi.emutran.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reusable D-pad focus treatment.
 *
 * Draws a white/light-gray border when the element has focus from the
 * hardware D-pad (or keyboard). Invisible when unfocused so it doesn't
 * clutter the screen during touch-only use.
 *
 * Usage:
 *   Modifier.dpadFocusable(interactionSource = remember { MutableInteractionSource() })
 *
 * Or for components that already own an InteractionSource (e.g. Button),
 * pass the same source so focus-state is shared:
 *   Button(interactionSource = src, modifier = Modifier.dpadFocusBorder(src))
 *
 * Color and width intentionally hard-coded to white / 2 dp — the design
 * rule is monochrome, so there's only one right answer for a focus ring.
 */

private val FocusRingColor = Color(0xFFFFFFFF)     // white
private val FocusRingColorDim = Color(0xFFB0B0B0)  // light-gray for secondary elements
private val FocusRingWidth = 2.dp
private val FocusRingRadius = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
private val FocusRingRadiusSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

/**
 * Adds a visible white focus ring when focused (D-pad / keyboard) and
 * makes the element focusable. Uses [MutableInteractionSource] internally
 * so callers don't have to provide one.
 *
 * [cornerRadius] controls the shape of the ring — pass 0.dp for a square.
 * [dimRing] uses a light-gray ring instead of white (for secondary elements).
 */
fun Modifier.dpadFocusable(
    cornerRadius: Dp = 12.dp,
    dimRing: Boolean = false,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val color = if (dimRing) FocusRingColorDim else FocusRingColor
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    this
        .focusable(interactionSource = src)
        .then(
            if (focused) Modifier.border(
                border = BorderStroke(FocusRingWidth, color),
                shape = shape,
            ) else Modifier
        )
}

/**
 * Lightweight variant that only draws the focus ring based on an existing
 * [MutableInteractionSource]. Use this when the component (Button, Card, etc.)
 * already manages its own InteractionSource — pass the same object here so
 * the ring reflects the real focused state without fighting the component.
 *
 * Does NOT call .focusable() — the component handles that itself.
 */
fun Modifier.dpadFocusBorder(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 12.dp,
    dimRing: Boolean = false,
): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val color = if (dimRing) FocusRingColorDim else FocusRingColor
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    if (focused) {
        this.border(
            border = BorderStroke(FocusRingWidth, color),
            shape = shape,
        )
    } else {
        this
    }
}
