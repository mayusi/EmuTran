package io.github.mayusi.emutran.ui.bios

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.mayusi.emutran.ui.common.dpadFocusable
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * BIOS Helper screen — turns the raw [BiosValidator] output into an actionable
 * per-system checklist that tells the user:
 *
 *  - Which BIOS files they still need.
 *  - Exactly which folder to drop them into (absolute path, copy-to-clipboard).
 *  - Whether a present dump is hash-verified good, present-but-unverified, or
 *    confirmed bad (wrong hash / corrupt / wrong region dump).
 *
 * Design rules (monochrome mandate, controller-first):
 *  - No hue. Verdicts are communicated by a text glyph + label, mirroring
 *    [HealthCheckScreen]'s StatusGlyphChip pattern.
 *  - Every interactive element (back, copy-path, re-scan) is D-pad navigable
 *    with a visible white focus ring via [dpadFocusBorder] / [dpadFocusable].
 *  - System cards are [dpadFocusable] scrollable items.
 *  - Re-scan button receives initial focus so the user can trigger an immediate
 *    re-run from the hardware D-pad without touching the screen first.
 *
 * Legal note displayed prominently: EmuTran never provides BIOS files.
 *
 * READ-ONLY: this screen never writes, deletes, or downloads any files.
 *
 * Navigation: entered from Dashboard via the "bios" route; [onBack] pops the
 * back-stack. Added to the NavHost by the wiring agent — do not edit
 * EmuTranApp.kt here.
 *
 * @param onBack Callback to pop the back stack (passed in from the NavHost).
 * @param vm     Injected via [hiltViewModel] — override only in tests/previews.
 */
@Composable
fun BiosHelperScreen(
    onBack: () -> Unit = {},
    vm: BiosHelperViewModel = hiltViewModel(),
) {
    val uiState by vm.state.collectAsStateWithLifecycle()

    // D-pad: Re-scan button requests initial focus so the user can instantly
    // trigger a re-run from the hardware D-pad without scrolling first.
    val rescanFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { rescanFocus.requestFocus() } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                BiosTopBar(onBack = onBack)
                HorizontalDivider(color = EmuTones.outlineDivider)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = EmuTones.outlineDivider)
                val rescanInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = vm::rescan,
                    interactionSource = rescanInteraction,
                    enabled = uiState !is BiosHelperUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(rescanFocus)
                        .dpadFocusBorder(rescanInteraction, cornerRadius = 50.dp),
                ) {
                    Text("Re-scan")
                }
            }
        },
    ) { innerPadding ->
        when (val s = uiState) {
            is BiosHelperUiState.Loading -> BiosLoadingContent(innerPadding)
            is BiosHelperUiState.NoRoot  -> BiosNoRootContent(innerPadding)
            is BiosHelperUiState.Loaded  -> BiosLoadedContent(state = s, innerPadding = innerPadding)
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun BiosTopBar(onBack: () -> Unit) {
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
            modifier = Modifier.dpadFocusBorder(backInteraction, cornerRadius = 50.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = EmuTones.onSurface,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = "BIOS Helper",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )
    }
}

// ── Loading state ─────────────────────────────────────────────────────────────

@Composable
private fun BiosLoadingContent(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = EmuTones.onSurface,
                trackColor = EmuTones.outlineDivider,
            )
            Text(
                text = "Scanning BIOS files…",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

// ── No-root state ─────────────────────────────────────────────────────────────

@Composable
private fun BiosNoRootContent(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "–",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = EmuTones.onSurfaceVar,
            )
            Text(
                text = "No storage root configured",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = EmuTones.onSurface,
            )
            Text(
                text = "Complete Setup first to tell EmuTran where your Emulation folder lives.",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

// ── Loaded state ──────────────────────────────────────────────────────────────

@Composable
private fun BiosLoadedContent(
    state: BiosHelperUiState.Loaded,
    innerPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Summary header — e.g. "3 of 7 systems ready".
        item(key = "summary") {
            BiosSummaryHeader(readySystems = state.readySystems, totalSystems = state.totalSystems)
            Spacer(Modifier.height(4.dp))
        }

        // Legal note — prominent, static, read once.
        item(key = "legal") {
            LegalNote()
            Spacer(Modifier.height(4.dp))
        }

        // One card per system.
        items(state.systems, key = { it.systemKey }) { entry ->
            BiosSystemCard(entry = entry)
        }
    }
}

// ── Summary header ────────────────────────────────────────────────────────────

@Composable
private fun BiosSummaryHeader(readySystems: Int, totalSystems: Int) {
    val text = when {
        totalSystems == 0 -> "No systems with expected BIOS files found."
        readySystems == totalSystems -> "All $totalSystems systems ready."
        readySystems == 0 -> "You still need BIOS files for all $totalSystems systems."
        else -> "$readySystems of $totalSystems systems ready — you still need files for ${totalSystems - readySystems}."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = EmuTones.onSurfaceVar,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ── Legal note ────────────────────────────────────────────────────────────────

@Composable
private fun LegalNote() {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.container),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "EmuTran never provides BIOS files — dump them from your own console. " +
                   "Drop the files into the folder shown below each system, then tap Re-scan.",
            style = MaterialTheme.typography.bodySmall,
            color = EmuTones.onSurfaceVar,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

// ── System card ───────────────────────────────────────────────────────────────

/**
 * One system card in the BIOS checklist.
 *
 * Layout:
 *  - Header row: system name + "Used by <emulator>"
 *  - Folder path row with a copy-to-clipboard action (controller-navigable)
 *  - Divider
 *  - Per-file checklist rows OR prose guidance for prose-only systems
 *
 * D-pad: the card is [dpadFocusable] (scroll + selection) and the copy-path
 * button is separately focusable via its own [dpadFocusBorder].
 */
@Composable
private fun BiosSystemCard(entry: BiosSystemEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable(cornerRadius = 14.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.surface),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // System name + readiness chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReadinessChip(entry = entry)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = EmuTones.onSurface,
                    )
                    Text(
                        text = "Used by: ${entry.usedBy}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmuTones.onSurfaceVar,
                    )
                }
            }

            // Folder path with copy button
            FolderPathRow(path = entry.folderPath)

            // File list or prose guidance
            if (entry.isProseSys) {
                HorizontalDivider(
                    color = EmuTones.outlineDivider,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                Text(
                    text = "See the emulator's own setup instructions — no standard filenames to validate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            } else if (entry.files.isNotEmpty()) {
                HorizontalDivider(
                    color = EmuTones.outlineDivider,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                entry.files.forEach { file ->
                    BiosFileRow(fileEntry = file)
                }
            }
        }
    }
}

// ── Readiness chip ────────────────────────────────────────────────────────────

/**
 * Small monochrome chip showing the system's overall readiness.
 *
 * Glyph conventions (matching HealthCheckScreen's StatusGlyphChip pattern):
 *  Ready (all files present/verified)  →  ✓
 *  Partial (some files missing)         →  !
 *  None (no files at all / prose sys)  →  –
 */
@Composable
private fun ReadinessChip(entry: BiosSystemEntry) {
    val (glyph, label) = when {
        entry.isProseSys                    -> "–" to "Manual"
        entry.files.isEmpty()               -> "–" to "No files"
        entry.isReady                       -> "✓" to "Ready"
        entry.readyCount > 0                -> "!" to "Partial"
        else                                -> "!" to "Needed"
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.container),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = EmuTones.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

// ── Folder path row ───────────────────────────────────────────────────────────

/**
 * Displays the absolute bios folder path and a controller-navigable
 * copy-to-clipboard button.
 *
 * The entire row is not focusable as one unit — instead the copy [IconButton]
 * carries its own [dpadFocusBorder] so D-pad users can land on it directly.
 */
@Composable
private fun FolderPathRow(path: String) {
    val context = LocalContext.current
    val copyInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            color = EmuTones.onSurfaceVar,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { copyPathToClipboard(context, path) },
            interactionSource = copyInteraction,
            modifier = Modifier.dpadFocusBorder(copyInteraction, cornerRadius = 50.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy path to clipboard",
                tint = EmuTones.onSurfaceVar,
            )
        }
    }
}

private fun copyPathToClipboard(context: Context, path: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("BIOS folder path", path))
}

// ── BIOS file row ─────────────────────────────────────────────────────────────

/**
 * One row in the per-file checklist inside a system card.
 *
 * Layout: verdict glyph chip | filename | optional note
 *
 * Verdict → glyph (monochrome, matching project convention):
 *  VERIFIED    → ✓  (hash confirmed good)
 *  PRESENT     → ✓  (present, no hash to verify)
 *  WRONG_HASH  → ✕  (present but bad hash — re-dump)
 *  MISSING     → !  (not found — needs to be dropped in)
 */
@Composable
private fun BiosFileRow(fileEntry: BiosFileEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Verdict glyph chip
        VerdictGlyphChip(verdict = fileEntry.verdict)

        // Filename + optional parenthetical note
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileEntry.filename,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = EmuTones.onSurface,
            )
            if (fileEntry.note.isNotEmpty()) {
                Text(
                    text = fileEntry.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }

        // Contextual action hint for actionable verdicts
        val hint = when (fileEntry.verdict) {
            BiosFileVerdict.WRONG_HASH -> "re-dump"
            BiosFileVerdict.MISSING    -> "needed"
            else                        -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

/**
 * Small chip showing the per-file verdict glyph + a short label.
 *
 * Glyph conventions:
 *  VERIFIED    ✓  — md5 confirmed against a published reference hash
 *  PRESENT     ✓  — present on disk; no reference hash available to confirm
 *  WRONG_HASH  ✕  — bad hash; the dump needs to be replaced
 *  MISSING     !  — file not found; needs to be obtained from own hardware
 */
@Composable
private fun VerdictGlyphChip(verdict: BiosFileVerdict) {
    val (glyph, label) = when (verdict) {
        BiosFileVerdict.VERIFIED    -> "✓" to "verified"
        BiosFileVerdict.PRESENT     -> "✓" to "present"
        BiosFileVerdict.WRONG_HASH  -> "✕" to "bad hash"
        BiosFileVerdict.MISSING     -> "!" to "missing"
    }

    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.container),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = glyph,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = EmuTones.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}
