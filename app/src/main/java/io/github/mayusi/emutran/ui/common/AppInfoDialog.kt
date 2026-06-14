package io.github.mayusi.emutran.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Lightweight info popup for a single downloadable item.
 *
 * Improvements (Task 4 A6):
 *  - Dialog bg = [EmuTones.containerHighest] via containerColor override.
 *  - Metadata labels ("Category:", "Source:", "Author:") for clarity.
 *  - Letter-avatar for identity (monochrome circle with app initial).
 *  - Optional [onInstall] callback: if non-null, shows a primary "Install"
 *    action button alongside Close so the dialog can queue an install without
 *    the caller needing a separate affordance. When null, only Close is shown
 *    — safe to use in view-only contexts.
 *  - Close button clearly focusable with D-pad focus ring.
 *
 * D-pad: focus is trapped inside the dialog. The primary action (Install if
 * present, else Close) receives initial focus so A-press acts immediately.
 * B / system back also dismisses.
 */
@Composable
fun AppInfoDialog(
    entry: AppEntry,
    onInstall: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val description = entry.about.ifBlank { "No description available." }

    // D-pad initial focus: primary action if install is available, else close.
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { primaryFocus.requestFocus() } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmuTones.containerHighest,
        titleContentColor = EmuTones.onSurface,
        textContentColor = EmuTones.onSurface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Monochrome letter-avatar for identity
                AppLetterAvatar(name = entry.name)
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmuTones.onSurface,
                )

                Spacer(Modifier.height(4.dp))

                // Labeled metadata — Category / Source / Author
                MetadataRow(label = "Category", value = entry.system.display)
                MetadataRow(label = "Source", value = entry.source.name.lowercase())
                if (entry.author.isNotBlank()) {
                    MetadataRow(label = "Author", value = entry.author)
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onInstall != null) {
                    // Primary: Install — gets initial focus when available.
                    val installInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            onInstall()
                            onDismiss()
                        },
                        interactionSource = installInteraction,
                        modifier = Modifier
                            .focusRequester(primaryFocus)
                            .dpadFocusBorder(installInteraction, cornerRadius = 8.dp),
                    ) {
                        Text("Install")
                    }
                }

                val closeInteraction = remember { MutableInteractionSource() }
                TextButton(
                    onClick = onDismiss,
                    interactionSource = closeInteraction,
                    modifier = Modifier
                        .then(if (onInstall == null) Modifier.focusRequester(primaryFocus) else Modifier)
                        .dpadFocusBorder(closeInteraction, cornerRadius = 8.dp),
                ) {
                    Text("Close")
                }
            }
        },
    )
}

/**
 * One labeled metadata row. The label is SemiBold white; the value is the
 * secondary [EmuTones.onSurfaceVar] color so there's clear hierarchy between
 * field name and content.
 */
@Composable
private fun MetadataRow(label: String, value: String) {
    Text(
        text = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = EmuTones.onSurface,
                )
            ) { append("$label: ") }
            withStyle(SpanStyle(color = EmuTones.onSurfaceVar)) {
                append(value)
            }
        },
        style = MaterialTheme.typography.labelMedium,
    )
}
