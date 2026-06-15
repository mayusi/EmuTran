package io.github.mayusi.emutran.ui.profile

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.truncatedNames
import io.github.mayusi.emutran.ui.theme.EmuTones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup Profile backup / restore screen.
 *
 * Two actions, both monochrome + D-pad navigable:
 *
 *  - **Export profile** — writes a small JSON snapshot of the current setup
 *    (storage root, picked emulators, dual-screen + GPU-driver flags) to
 *    <storageRoot>/Emulation/EmuTran/profile.json and reports the path via a
 *    snackbar + an on-screen "Saved to …" line.
 *
 *  - **Import profile** — opens a system file picker (ACTION_OPEN_DOCUMENT,
 *    application/json), reads the bytes, and applies them. A successful import
 *    shows how many apps were applied / dropped, whether the storage root
 *    changed, and offers to jump to the Progress screen (to run the restored
 *    setup) or back to the Dashboard. Malformed files are reported clearly.
 *
 * Design follows the project monochrome mandate (EmuTones, no hue) and mirrors
 * [io.github.mayusi.emutran.ui.health.HealthCheckScreen]'s top-bar + card idiom.
 *
 * @param onBack pop back to the previous destination.
 * @param onImported invoked when the user, after a successful import, chooses
 *   to continue. [toProgress] is true for "Run restored setup" (→ Progress) and
 *   false for "Back to dashboard" (→ Dashboard). The caller owns the actual
 *   navigation so this screen stays nav-agnostic.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit = {},
    onImported: (toProgress: Boolean) -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()

    // D-pad: land on Back so B / A both work immediately.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { backFocus.requestFocus() } catch (_: Exception) {}
    }

    // System file picker for import — reads the chosen JSON document's bytes
    // and hands the string to the ViewModel.
    //
    // The moment the picker returns a non-null uri we flip the ViewModel into
    // its Working state (PROF-5) so the button shows progress across the whole
    // read+validate span, and so re-launching the picker while a read is in
    // flight is guarded (the launch site checks `importing`).
    //
    // The document is read with a hard size cap (PROF-3 sec / QUAL-5): a real
    // profile is a few hundred bytes, so anything over MAX_PROFILE_BYTES is
    // rejected before we pull it into memory.
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.beginImport()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                readProfileDocument(context, uri)
            }
            when (outcome) {
                is DocumentReadOutcome.Text -> vm.importProfile(outcome.value)
                is DocumentReadOutcome.TooLarge ->
                    vm.reportImportReadError(
                        "That file is too large to be an EmuTran profile — pick the small profile.json you exported.",
                    )
                is DocumentReadOutcome.Unreadable ->
                    vm.reportImportReadError("Could not read the selected file")
            }
        }
    }

    // Export terminal states → snackbar. Reset after showing so the action can
    // be repeated.
    LaunchedEffect(exportState) {
        when (val s = exportState) {
            is ProfileViewModel.ExportUiState.Done -> {
                snackbarHostState.showSnackbar("Saved to ${s.path}")
                vm.consumeExportState()
            }
            is ProfileViewModel.ExportUiState.Failed -> {
                snackbarHostState.showSnackbar("Export failed: ${s.reason}")
                vm.consumeExportState()
            }
            else -> Unit
        }
    }

    // Import failures are rendered as a PERSISTENT inline block inside the
    // Import card (PROF-2) rather than a transient snackbar, so the user can
    // read the guidance and retry. No auto-consume here.

    val hasPicks by vm.hasPicks.collectAsStateWithLifecycle()
    val exporting = exportState is ProfileViewModel.ExportUiState.Working
    val importing = importState is ProfileViewModel.ImportUiState.Working
    val lastExportPath = (exportState as? ProfileViewModel.ExportUiState.Done)?.path

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                ProfileTopBar(onBack = onBack, backFocus = backFocus)
                HorizontalDivider(color = EmuTones.outlineDivider)
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = EmuTones.containerHighest,
                    contentColor = EmuTones.onSurface,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Carry your setup choices to a new device or recover after a " +
                    "reinstall. The profile stores your storage root, picked emulators, " +
                    "and setup flags — not ROMs, saves, or BIOS.",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )

            // ── Export card ─────────────────────────────────────────────────
            ActionCard(
                icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = EmuTones.onSurface) },
                title = "Export profile",
                body = "Write a JSON snapshot of your current setup to your storage folder.",
            ) {
                val exportInteraction = remember { MutableInteractionSource() }
                // PROF-4: with zero picked emulators there's nothing meaningful
                // to back up. Disable Export and explain why rather than writing
                // an empty profile the user can't make sense of later.
                val exportEnabled = !exporting && hasPicks
                Button(
                    onClick = { if (exportEnabled) vm.exportProfile() },
                    enabled = exportEnabled,
                    interactionSource = exportInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(exportInteraction, cornerRadius = 50.dp),
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Exporting…", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("Export profile", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (!hasPicks) {
                    Text(
                        text = "Nothing to back up yet — pick some emulators first.",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmuTones.onSurfaceVar,
                    )
                }
                if (lastExportPath != null) {
                    Text(
                        text = "Saved to $lastExportPath",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmuTones.onSurfaceVar,
                    )
                }
            }

            // ── Import card ─────────────────────────────────────────────────
            ActionCard(
                icon = { Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = EmuTones.onSurface) },
                title = "Import profile",
                body = "Pick a profile.json and apply its choices to this device.",
            ) {
                val importInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        if (!importing) {
                            // application/json — some pickers tag exported files as
                            // text/plain or octet-stream, so accept those too.
                            openDocLauncher.launch(
                                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"),
                            )
                        }
                    },
                    enabled = !importing,
                    interactionSource = importInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(importInteraction, cornerRadius = 50.dp),
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = EmuTones.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Importing…", style = MaterialTheme.typography.labelLarge)
                    } else {
                        Text("Import profile", style = MaterialTheme.typography.labelLarge)
                    }
                }

                // Success summary + continue actions, shown inline so the user
                // can choose where to go next.
                (importState as? ProfileViewModel.ImportUiState.Done)?.let { done ->
                    ImportSuccessSection(
                        done = done,
                        onUseImportedRoot = { path -> vm.applyProposedRoot(path) },
                        onKeepCurrentRoot = { vm.keepCurrentRoot() },
                        onRunRestored = {
                            vm.consumeImportState()
                            onImported(true)
                        },
                        onBackToDashboard = {
                            vm.consumeImportState()
                            onImported(false)
                        },
                    )
                }

                // PROF-2: persistent inline error block (with retry) for a
                // failed import — friendlier and stickier than a snackbar.
                (importState as? ProfileViewModel.ImportUiState.Failed)?.let { failed ->
                    ImportErrorSection(
                        reason = failed.reason,
                        onRetry = {
                            vm.consumeImportState()
                            openDocLauncher.launch(
                                arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"),
                            )
                        },
                    )
                }
            }
        }
    }
}

// ── Top bar ─────────────────────────────────────────────────────────────────

@Composable
private fun ProfileTopBar(onBack: () -> Unit, backFocus: FocusRequester) {
    val backInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            interactionSource = backInteraction,
            modifier = Modifier
                .focusRequester(backFocus)
                .dpadFocusBorder(backInteraction, cornerRadius = 50.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = EmuTones.onSurface,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Backup / Restore",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )
    }
}

// ── Action card scaffold ──────────────────────────────────────────────────────

@Composable
private fun ActionCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.surface),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                icon()
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EmuTones.onSurface,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = EmuTones.onSurfaceVar,
            )
            content()
        }
    }
}

// ── Import success section ────────────────────────────────────────────────────

@Composable
private fun ImportSuccessSection(
    done: ProfileViewModel.ImportUiState.Done,
    onUseImportedRoot: (path: String) -> Unit,
    onKeepCurrentRoot: () -> Unit,
    onRunRestored: () -> Unit,
    onBackToDashboard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = EmuTones.outlineDivider)

        val summary = buildString {
            append(
                "Applied ${done.appliedCount} app" +
                    if (done.appliedCount == 1) "" else "s",
            )
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = EmuTones.onSurface,
        )

        // PROF-3: name the emulators that couldn't be restored (resolved to
        // display names where possible), truncating long lists like the
        // catalog-diff banner does.
        if (done.droppedNames.isNotEmpty()) {
            Text(
                text = "Couldn't restore: ${truncatedNames(done.droppedNames)} (no longer available).",
                style = MaterialTheme.typography.labelMedium,
                color = EmuTones.onSurfaceVar,
            )
        }

        // FIX 1: confirm-before-adopt for the imported storage root. The
        // proposal is only non-null when it differs from the current root; when
        // invalid we say so and never offer to apply it.
        done.proposedRoot?.let { proposed ->
            StorageRootConfirm(
                proposed = proposed,
                onUse = { onUseImportedRoot(proposed.path) },
                onKeep = onKeepCurrentRoot,
            )
        }

        val runInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onRunRestored,
            interactionSource = runInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(runInteraction, cornerRadius = 50.dp),
        ) {
            Text("Run restored setup", style = MaterialTheme.typography.labelLarge)
        }

        val dashInteraction = remember { MutableInteractionSource() }
        TextButton(
            onClick = onBackToDashboard,
            interactionSource = dashInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .dpadFocusBorder(dashInteraction, cornerRadius = 8.dp),
        ) {
            Text("Back to dashboard", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Storage-root confirmation (confirm before adopt) ──────────────────────────

/**
 * Asks the user whether to adopt the storage root from the imported profile.
 *
 * When [ProfileViewModel.ProposedRootUi.isValid] is true we offer "Use imported
 * path" (which persists it) alongside "Keep current". When it's false the path
 * can't be used on this device, so we say so and only offer "Keep current" —
 * the imported path is never silently adopted.
 */
@Composable
private fun StorageRootConfirm(
    proposed: ProfileViewModel.ProposedRootUi,
    onUse: () -> Unit,
    onKeep: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.containerHighest),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (proposed.isValid) {
                Text(
                    text = "This profile was set up with storage at ${proposed.path}. " +
                        "Use it on this device?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmuTones.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val useInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = onUse,
                        interactionSource = useInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.ButtonMinHeight)
                            .dpadFocusBorder(useInteraction, cornerRadius = 50.dp),
                    ) {
                        Text("Use imported path", style = MaterialTheme.typography.labelLarge)
                    }
                    val keepInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = onKeep,
                        interactionSource = keepInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dimens.ButtonMinHeight)
                            .dpadFocusBorder(keepInteraction, cornerRadius = 50.dp),
                    ) {
                        Text("Keep current", style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Text(
                    text = "This profile's storage path (${proposed.path}) isn't valid on " +
                        "this device, so it can't be used here. Keeping your current " +
                        "storage location.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmuTones.onSurface,
                )
                val keepInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = onKeep,
                    interactionSource = keepInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(keepInteraction, cornerRadius = 50.dp),
                ) {
                    Text("Keep current", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ── Import error section (persistent inline) ──────────────────────────────────

/**
 * PROF-2: a persistent, friendly inline error for a failed import, with a
 * "Try another file" action that re-opens the picker.
 */
@Composable
private fun ImportErrorSection(
    reason: String,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = EmuTones.outlineDivider)
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = EmuTones.onSurface,
        )
        val retryInteraction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onRetry,
            interactionSource = retryInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(retryInteraction, cornerRadius = 50.dp),
        ) {
            Text("Try another file", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Document read with size cap ───────────────────────────────────────────────

/** Outcome of reading a picked profile document. */
private sealed interface DocumentReadOutcome {
    data class Text(val value: String) : DocumentReadOutcome
    data object TooLarge : DocumentReadOutcome
    data object Unreadable : DocumentReadOutcome
}

/**
 * Read the picked [uri] as UTF-8 text, capping the read at
 * [ProfileViewModel.MAX_PROFILE_BYTES] (QUAL-5 / PROF-3 sec). First consults the
 * provider's reported SIZE column for a cheap up-front reject; then bounds the
 * actual stream read so a provider that lies (or omits the size) still can't
 * pull an oversized blob into memory.
 */
private fun readProfileDocument(
    context: android.content.Context,
    uri: Uri,
): DocumentReadOutcome {
    val resolver = context.contentResolver
    val cap = ProfileViewModel.MAX_PROFILE_BYTES

    // Cheap pre-check: reported size, when available.
    val reportedSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
        } ?: -1L
    }.getOrDefault(-1L)
    if (reportedSize > cap) return DocumentReadOutcome.TooLarge

    return runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            // Read up to cap+1 bytes; if we hit cap+1 the file is over the limit.
            val buffer = ByteArray((cap + 1).toInt())
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            if (total > cap) {
                DocumentReadOutcome.TooLarge
            } else {
                DocumentReadOutcome.Text(String(buffer, 0, total, Charsets.UTF_8))
            }
        } ?: DocumentReadOutcome.Unreadable
    }.getOrDefault(DocumentReadOutcome.Unreadable)
}
