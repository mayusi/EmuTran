package io.github.mayusi.emutran.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Shared "What's new / self-update" bottom-sheet content used by both
 * [DashboardScreen] and [AboutScreen].
 *
 * @param version           Cleaned version string (e.g. "0.3.0"). Shown in the title.
 * @param changelogMarkdown GitHub release body. Rendered via [MarkdownText].
 *                          When blank, shows a "No release notes for this version."
 *                          placeholder so the layout doesn't collapse to an empty gap.
 * @param downloadingPercent Null when not downloading; 0-100 during download.
 *                           Drives the progress bar + percent label.
 * @param onUpdateNow       Called when the user taps "Update now". Only shown when
 *                          [downloadingPercent] is null (not yet downloading).
 * @param onSkip            Called when the user taps "Skip this release". Pass null to
 *                          hide the Skip button (e.g. AboutScreen doesn't have a skip flow).
 * @param onDismiss         Called for "Remind me later" (not downloading) OR
 *                          "Cancel download" (when downloading). Either resets state
 *                          or cancels the in-flight job depending on caller's logic.
 */
@Composable
fun SelfUpdateSheet(
    version: String,
    changelogMarkdown: String,
    downloadingPercent: Int?,
    onUpdateNow: () -> Unit,
    onSkip: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Title always shows the version even during download (FIX C1 version-in-title).
        Text(
            text = "What's new in v$version",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )

        // FIX C1: show placeholder when changelog is blank; don't render empty gap.
        if (changelogMarkdown.isNotBlank()) {
            MarkdownText(
                markdown = changelogMarkdown,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = "No release notes for this version.",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        }

        // Download progress bar — shown when in Downloading state.
        if (downloadingPercent != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { downloadingPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = EmuTones.onSurface,
                    trackColor = EmuTones.outlineDivider,
                )
                // a11y: liveRegion.Polite so TalkBack announces percent changes
                // without interrupting other speech.
                Text(
                    text = "Downloading… $downloadingPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurfaceVar,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        // Action buttons — vary by state.
        if (downloadingPercent == null) {
            // Not yet downloading: show "Update now" + optional "Skip this release" row,
            // then "Remind me later" as a text button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val updateInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = onUpdateNow,
                    interactionSource = updateInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(updateInteraction, cornerRadius = 50.dp),
                ) {
                    Text("Update now")
                }

                // "Skip this release" is optional — null hides the button.
                if (onSkip != null) {
                    val skipInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = onSkip,
                        interactionSource = skipInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.ButtonMinHeight)
                            .dpadFocusBorder(skipInteraction, cornerRadius = 50.dp),
                    ) {
                        Text("Skip this release")
                    }
                }
            }

            val dismissInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = onDismiss,
                interactionSource = dismissInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusBorder(dismissInteraction, cornerRadius = 8.dp),
            ) {
                Text(
                    text = "Remind me later",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmuTones.onSurfaceVar,
                )
            }
        } else {
            // Downloading: show an ENABLED "Cancel download" button (FIX C2).
            // The previous pattern was a disabled "Downloading…" button which gave
            // the user no way to abort. Now it's enabled and calls onDismiss which
            // cancels the job in the ViewModel.
            val cancelInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onDismiss,
                enabled = true,
                interactionSource = cancelInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonMinHeight)
                    .dpadFocusBorder(cancelInteraction, cornerRadius = 50.dp),
            ) {
                Text("Cancel download")
            }
        }
    }
}
