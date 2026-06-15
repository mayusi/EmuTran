package io.github.mayusi.emutran.ui.progress

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.theme.EmuTones

@Composable
fun ProgressScreen(
    onDone: (success: Boolean) -> Unit,
    onGoToDashboard: () -> Unit = {},
    vm: ProgressViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.start() }

    // FIX 6: intercept Back while setup is in progress to avoid orphaning the
    // foreground service. Show a confirmation dialog; only navigate away on confirm.
    val isInProgress = state is ProgressViewModel.State.Scaffolding ||
        state is ProgressViewModel.State.Resolving ||
        state is ProgressViewModel.State.Downloading ||
        state is ProgressViewModel.State.StagingDrivers ||
        state is ProgressViewModel.State.Installing ||
        state is ProgressViewModel.State.OfflineWarning
    var showCancelDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = isInProgress) {
        showCancelDialog = true
    }

    // Offline pre-flight dialog — shown when the pipeline is suspended at
    // State.OfflineWarning, waiting for the user's decision.
    if (state == ProgressViewModel.State.OfflineWarning) {
        val continueInteraction = remember { MutableInteractionSource() }
        val cancelInteraction   = remember { MutableInteractionSource() }
        val continueFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            try { continueFocus.requestFocus() } catch (_: Exception) {}
        }
        AlertDialog(
            onDismissRequest = { /* non-dismissible — user must choose */ },
            title = { Text("No network detected") },
            text  = {
                Text(
                    "Your Emulation folders will still be created, but emulator " +
                        "downloads will fail without a connection. Continue anyway?",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { vm.confirmOffline(true) },
                    interactionSource = continueInteraction,
                    modifier = Modifier
                        .focusRequester(continueFocus)
                        .dpadFocusBorder(continueInteraction, cornerRadius = 50.dp),
                ) { Text("Continue anyway") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { vm.confirmOffline(false) },
                    interactionSource = cancelInteraction,
                    modifier = Modifier.dpadFocusBorder(cancelInteraction, cornerRadius = 50.dp),
                ) { Text("Cancel") }
            },
            containerColor    = EmuTones.containerHighest,
            textContentColor  = EmuTones.onSurface,
            titleContentColor = EmuTones.onSurface,
        )
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel setup?") },
            text  = {
                Text(
                    "Downloaded files will be kept but setup will stop. " +
                        "You can restart it from the dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                val confirmInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        showCancelDialog = false
                        // FIX 6: reset state to Idle (service sees non-terminal state,
                        // coroutine scope is cancelled when ViewModel is cleared on nav away).
                        vm.cancelAndReset()
                        onGoToDashboard()
                    },
                    interactionSource = confirmInteraction,
                    modifier = Modifier.dpadFocusBorder(confirmInteraction, cornerRadius = 50.dp),
                ) { Text("Cancel setup") }
            },
            dismissButton = {
                val dismissInteraction = remember { MutableInteractionSource() }
                TextButton(
                    onClick = { showCancelDialog = false },
                    interactionSource = dismissInteraction,
                    modifier = Modifier.dpadFocusBorder(dismissInteraction, cornerRadius = 8.dp),
                ) { Text("Keep going") }
            },
            containerColor = EmuTones.containerHighest,
            textContentColor = EmuTones.onSurface,
            titleContentColor = EmuTones.onSurface,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        // Step indicator — step 6 of 6.
        StepIndicator(current = 6)

        Text(
            text  = "Setting up…",
            style = MaterialTheme.typography.headlineMedium,
        )

        when (val s = state) {
            ProgressViewModel.State.Idle -> {
                Text("Preparing…", style = MaterialTheme.typography.bodyMedium)
            }

            ProgressViewModel.State.OfflineWarning -> {
                // Show a neutral waiting line beneath the dialog so the
                // screen doesn't look empty while the dialog is visible.
                Text(
                    text  = "Waiting for your decision…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ProgressViewModel.State.Scaffolding -> {
                PhaseLine("Folders", s.done, s.total, s.label)
            }

            is ProgressViewModel.State.Resolving -> {
                PhaseLine(
                    "Looking up downloads",
                    s.done, s.total,
                    "Checking ${s.currentApp}…",
                )
            }

            is ProgressViewModel.State.Downloading -> {
                // Download progress wrapped in a card for legibility.
                DownloadCard(
                    label          = "Downloading ${s.done + 1} of ${s.total} — ${s.currentApp}",
                    overallProgress = if (s.total > 0) s.done.toFloat() / s.total else 0f,
                    currentBytes   = s.currentBytes,
                    totalBytes     = s.currentTotalBytes,
                )
            }

            is ProgressViewModel.State.StagingDrivers -> {
                Text(
                    text  = "Setting up GPU drivers…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text  = "Downloading ${s.assetCount} files from " +
                        "AdrenoToolsDrivers ${s.releaseTag} into " +
                        "Emulation/tools/turnip/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ProgressViewModel.State.Installing -> {
                PhaseLine(
                    "Installing",
                    s.done, s.total,
                    if (s.silent) "Installing silently — ${s.currentApp}"
                    else "Tap Install when prompted — ${s.currentApp}",
                )
                Text(
                    text  = if (s.silent) {
                        "Shizuku is handling installs silently. Nothing to tap — " +
                            "just wait for the queue to finish."
                    } else {
                        "Android will show an install dialog for each app. " +
                            "Tap Install in each one to continue."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is ProgressViewModel.State.Done -> {
                // Don't show a triumphant "All done!" when nothing actually
                // installed and everything failed — that leaves the user
                // staring at a success screen for a broken run.
                val nothingInstalled = s.installed.isEmpty()
                val allFailed = nothingInstalled && s.failed.isNotEmpty()
                if (allFailed) {
                    Text(
                        text  = "Nothing was installed",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    val rateLimited = s.failed.any { (_, reason) -> "403" in reason }
                    Text(
                        text  = if (rateLimited) {
                            "Every app failed to download. This is likely GitHub " +
                                "rate limiting (HTTP 403) — unauthenticated requests " +
                                "are capped at 60/hour. Wait a while and try again."
                        } else {
                            "Every app failed to download. Check your internet " +
                                "connection and try again; see the per-app reasons below."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text  = "All done!",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (s.installed.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Installed (${s.installed.size}):", style = MaterialTheme.typography.titleSmall)
                    // Glyph prefix: check mark for installed items.
                    // a11y: override contentDescription so TalkBack says "Installed: <name>"
                    // instead of "check mark <name>".
                    s.installed.forEach { name ->
                        Text(
                            text  = "✓  $name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmuTones.onSurface,
                            modifier = Modifier.semantics {
                                contentDescription = "Installed: $name"
                            },
                        )
                    }
                }
                if (s.skipped.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text  = "Already had (${s.skipped.size}):",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    // Glyph prefix: dot for skipped items.
                    // a11y: override contentDescription so TalkBack says "Already had: <name>"
                    // instead of "middle dot <name>".
                    s.skipped.forEach { name ->
                        Text(
                            text  = "·  $name",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EmuTones.onSurfaceVar,
                            modifier = Modifier.semantics {
                                contentDescription = "Already had: $name"
                            },
                        )
                    }
                }
                if (s.failed.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text  = "Failed (${s.failed.size}):",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // Glyph prefix: × for failed items.
                    // a11y: override contentDescription so TalkBack says "Failed: <name> — <reason>"
                    // instead of "multiplication sign <name> — <reason>".
                    s.failed.forEach { (name, reason) ->
                        Text(
                            text  = "✗  $name — $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics {
                                contentDescription = "Failed: $name — $reason"
                            },
                        )
                    }
                }

                when (val d = s.drivers) {
                    is ProgressViewModel.DriverSummary.Staged -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text  = "GPU drivers ready",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text  = "${d.files.size} driver zips placed in " +
                                "Emulation/tools/turnip/ (release ${d.releaseTag}).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text  = "These are optional performance drivers for " +
                                "Switch and PS Vita emulators (Eden, Yuzu, " +
                                "Vita3K, Skyline). To use one:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text  = "  1. Open the emulator's Settings → " +
                                "Graphics or GPU.\n" +
                                "  2. Find \"Load Custom Driver\" (or similar).\n" +
                                "  3. Pick one of the zips from " +
                                "Emulation/tools/turnip/.\n" +
                                "  4. Try the Qualcomm zip first; switch to a " +
                                "Turnip variant if you want to experiment.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text  = "Full explanation: " +
                                "Emulation/tools/turnip/README.txt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ProgressViewModel.DriverSummary.Skipped -> {
                        // Quiet when intentionally off — no need to highlight.
                    }
                    is ProgressViewModel.DriverSummary.Failed -> {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text  = "GPU drivers failed: ${d.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (s.failed.isNotEmpty()) {
                    val retryInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick           = { vm.retryFailed() },
                        interactionSource = retryInteraction,
                        modifier          = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.ButtonMinHeight)
                            .dpadFocusBorder(retryInteraction, cornerRadius = 50.dp),
                    ) { Text("Retry failed (${s.failed.size})") }
                }

                val doneFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try { doneFocus.requestFocus() } catch (_: Exception) {}
                }
                // FIX 4: pass whether this was a successful run so DoneScreen
                // can show the appropriate hero/title (check vs warning icon).
                val runSucceeded = s.installed.isNotEmpty() || s.skipped.isNotEmpty()
                val doneInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick           = { onDone(runSucceeded) },
                    interactionSource = doneInteraction,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(doneFocus)
                        .dpadFocusBorder(doneInteraction, cornerRadius = 50.dp),
                ) { Text("Done") }
            }

            is ProgressViewModel.State.Failed -> {
                Text(
                    text  = "Setup failed",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text  = s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                // FIX 3: Failed state must not be a dead-end — give the user two exits.
                // "Retry" is the primary action; "Go to Dashboard" is a secondary escape.
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    try { retryFocus.requestFocus() } catch (_: Exception) {}
                }
                val retryInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick           = { vm.retryAll() },
                    interactionSource = retryInteraction,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(retryFocus)
                        .dpadFocusBorder(retryInteraction, cornerRadius = 50.dp),
                ) { Text("Retry") }

                val dashInteraction = remember { MutableInteractionSource() }
                TextButton(
                    onClick           = onGoToDashboard,
                    interactionSource = dashInteraction,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .dpadFocusBorder(dashInteraction, cornerRadius = 8.dp),
                ) {
                    Text(
                        "Go to Dashboard",
                        color = EmuTones.onSurfaceVar,
                    )
                }
            }

            // FIX 1: Cancelled is a terminal state emitted by cancelAndReset().
            // The cancel dialog calls onGoToDashboard() immediately after
            // cancelAndReset(), so by the time this branch would render the
            // composable is already leaving the back-stack. Render nothing to
            // avoid a flash of unexpected UI if composition runs one extra frame.
            ProgressViewModel.State.Cancelled -> Unit
        }
    }
}

/**
 * Download progress wrapped in an EmuTones.surface card for legibility.
 *
 * Shows: app name + overall queue bar, then per-file byte progress bar
 * (only when byte counts are known — i.e. the server sent Content-Length).
 */
@Composable
private fun DownloadCard(
    label: String,
    overallProgress: Float,
    currentBytes: Long,
    totalBytes: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = EmuTones.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            if (totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { currentBytes.toFloat() / totalBytes },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text  = "${humanBytes(currentBytes)} / ${humanBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PhaseLine(label: String, done: Int, total: Int, detail: String) {
    Text(
        text  = "$label — $done / $total",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    LinearProgressIndicator(
        progress = { if (total > 0) done.toFloat() / total else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text  = detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun humanBytes(bytes: Long): String = when {
    bytes <= 0            -> "?"
    bytes < 1024          -> "$bytes B"
    bytes < 1024L * 1024  -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else                  -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
