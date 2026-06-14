package io.github.mayusi.emutran.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Reusable controller-aware emulator card.
 *
 * D-pad behaviour: the card itself is focusable — pressing A-button
 * (DPAD_CENTER) triggers [onClick]. Focus is communicated purely through
 * visual weight: scale springs to ~1.05x + bg switches to
 * [EmuTones.containerHigh] + 1.5dp white border. No glow, no color.
 *
 * Rest state: [EmuTones.surface] with a 1dp [EmuTones.outlineRest] border.
 *
 * Action rows are separate composable slots passed by the caller so the
 * card is reusable across installed / available / featured contexts.
 *
 * @param name          App display name.
 * @param secondaryLine Short subtitle shown in secondary text style (e.g. system
 *                      tag, version). Pass null to hide the line.
 * @param onClick       Called when the card is tapped or A-button pressed.
 * @param interactionSource Shared [MutableInteractionSource] — pass the SAME
 *                      source to any clickable modifier inside [bottomContent]
 *                      that should also reflect focus.
 * @param modifier      External modifiers; do not pass focus-visuals here as
 *                      the card handles its own.
 * @param topEndBadge   Optional badge composable pinned to the top-end corner
 *                      (e.g. "Installed" pill, "Update" chip).
 * @param bottomContent Action row composable placed below the text block.
 */
@Composable
fun EmulatorCard(
    name: String,
    secondaryLine: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    topEndBadge: (@Composable () -> Unit)? = null,
    bottomContent: (@Composable () -> Unit)? = null,
) {
    val focused by interactionSource.collectIsFocusedAsState()

    // D-pad focus: spring-animate scale up to ~1.05x on focus.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "cardScale",
    )

    val bgColor = if (focused) EmuTones.containerHigh else EmuTones.surface
    val borderColor = if (focused) Color.White else EmuTones.outlineRest
    val borderWidth = if (focused) 1.5.dp else 1.dp

    Surface(
        onClick = onClick,
        modifier = modifier.scale(scale),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(borderWidth, borderColor),
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Header row: avatar + name + badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Monochrome letter-avatar circle
                AppLetterAvatar(name = name)

                Spacer(Modifier.width(8.dp))

                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = EmuTones.onSurface,
                )

                if (topEndBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    topEndBadge()
                }
            }

            // Secondary subtitle (system tag, version, etc.)
            if (secondaryLine != null) {
                Text(
                    text = secondaryLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurfaceVar,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Action row
            if (bottomContent != null) {
                bottomContent()
            }
        }
    }
}

// ── Small shared composables used by callers ────────────────────────────────

/**
 * Monochrome circular letter avatar. Takes the first character of [name],
 * uppercased. Container = [EmuTones.container], text = [EmuTones.onSurface].
 * Purely decorative — adds identity to densely listed cards.
 */
@Composable
fun AppLetterAvatar(
    name: String,
    modifier: Modifier = Modifier,
) {
    val letter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(32.dp)
            .background(color = EmuTones.container, shape = CircleShape),
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurface,
        )
    }
}

/**
 * Outlined chip. Used for "Update vX.X" badge — white border on dark bg,
 * labelMedium weight text. [EmuTones.container] fill with white border.
 *
 * Distinct from [SmallStatusPill] which has a filled solid bg.
 */
@Composable
fun UpdateChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = EmuTones.container,
        border = BorderStroke(1.dp, Color.White),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Small filled status pill (for "Installed", "Recommended" etc.).
 * Slightly more visual weight than [UpdateChip] since it has a solid bg.
 */
@Composable
fun SmallStatusPill(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Update button — white outlined style. Full-width inside the card's action
 * row. Disabled while [busy] is true; shows "Updating…" text during update.
 * Shows update version when [updateLabel] is non-null.
 */
@Composable
fun CardUpdateButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    updateLabel: String? = null,
) {
    val label = when {
        busy -> "Updating…"
        updateLabel != null -> updateLabel
        else -> "Update"
    }
    OutlinedButton(
        onClick = onClick,
        enabled = !busy,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, if (busy) EmuTones.outlineRest else Color.White),
        modifier = modifier,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = EmuTones.onSurfaceVar,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Uninstall button — ghost/text style so it has lower visual weight than
 * [CardUpdateButton]. Destructive action requires intentional tap but
 * shouldn't be as loud as the primary update CTA.
 */
@Composable
fun CardUninstallButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = !busy,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = EmuTones.onSurfaceVar,
            disabledContentColor = EmuTones.outlineDivider,
        ),
        modifier = modifier,
    ) {
        Text("Uninstall", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Install / Get button used on available (not-yet-installed) cards.
 * Full-width primary Button style.
 */
@Composable
fun CardInstallButton(
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !busy,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        modifier = modifier,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            if (busy) "Installing…" else "Install",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
