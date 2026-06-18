package io.github.mayusi.emutran.ui.romsort

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.data.storage.AllFilesAccess
import io.github.mayusi.emutran.domain.roms.RomClassifier
import io.github.mayusi.emutran.domain.scaffold.FolderSpec
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * ROM Sort screen — scans for ROM files the user has already copied onto the
 * device and offers to MOVE them into the correct Emulation/roms/<system>/
 * folders.
 *
 * This screen NEVER downloads files, NEVER deletes files without a successful
 * move, and NEVER accesses anything beyond the user's own files.
 *
 * Entry point for the nav graph: [RomSortScreen].
 * Route: "rom_sort"
 *
 * @param onBack Pop back to the previous destination (caller handles nav).
 * @param vm     Injected by Hilt; override in tests.
 */
@Composable
fun RomSortScreen(
    onBack: () -> Unit = {},
    vm: RomSortViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // D-pad: Back button gets initial focus so B / Circle navigates out.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { backFocus.requestFocus() } catch (_: Exception) {}
    }

    // Re-check permission each time the screen is (re-)entered, e.g. after the
    // user grants permission in Settings and returns.
    LaunchedEffect(Unit) {
        vm.checkPermission()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                RomSortTopBar(onBack = onBack, backFocus = backFocus)
                HorizontalDivider(color = EmuTones.outlineDivider)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                is RomSortViewModel.ScreenState.NeedsPermission ->
                    PermissionGateContent(
                        allFilesAccess = vm.accessHelper(),
                        onGranted = { vm.checkPermission() },
                    )

                is RomSortViewModel.ScreenState.Idle ->
                    IdleContent(onScan = { vm.scan() })

                is RomSortViewModel.ScreenState.Scanning ->
                    ScanningContent()

                is RomSortViewModel.ScreenState.Results ->
                    ResultsContent(
                        state          = s,
                        onMoveOne      = { vm.moveOne(it) },
                        onMoveAll      = { vm.moveAllConfident() },
                        onMoveAssigned = { vm.moveAssigned(it) },
                        onSkip         = { vm.skipEntry(it) },
                        onRescan       = { vm.scan() },
                        vm             = vm,
                    )

                is RomSortViewModel.ScreenState.Moving ->
                    MovingContent(done = s.doneCount, total = s.totalCount)

                is RomSortViewModel.ScreenState.Done ->
                    DoneContent(state = s, onReset = { vm.reset() }, onBack = onBack)

                is RomSortViewModel.ScreenState.Error ->
                    ErrorContent(message = s.message, onRetry = { vm.scan() }, onBack = onBack)
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun RomSortTopBar(onBack: () -> Unit, backFocus: FocusRequester) {
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
            text = "Sort ROMs",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )
    }
}

// ── Permission gate ───────────────────────────────────────────────────────────

@Composable
private fun PermissionGateContent(
    allFilesAccess: AllFilesAccess,
    onGranted: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Storage access required",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurface,
        )
        Text(
            text = "ROM Sort needs \"All files access\" to scan your storage for ROM files. " +
                "Without it the scan cannot read files outside the app's private folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurfaceVar,
        )

        val openInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = { context.startActivity(allFilesAccess.requestIntent()) },
            interactionSource = openInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(openInteraction, cornerRadius = 50.dp),
        ) {
            Text("Open storage settings", style = MaterialTheme.typography.labelLarge)
        }

        val checkInteraction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onGranted,
            interactionSource = checkInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(checkInteraction, cornerRadius = 50.dp),
        ) {
            Text("I've granted access — continue", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Idle state ────────────────────────────────────────────────────────────────

@Composable
private fun IdleContent(onScan: () -> Unit) {
    // D-pad: Scan button gets initial focus in this sub-state.
    val scanFocus       = remember { FocusRequester() }
    val scanInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        try { scanFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DisclaimerCard()

        Button(
            onClick = onScan,
            interactionSource = scanInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(scanFocus)
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(scanInteraction, cornerRadius = 50.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Scan for ROMs", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Disclaimer card ───────────────────────────────────────────────────────────

@Composable
private fun DisclaimerCard() {
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = EmuTones.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Your files, moved — never downloaded",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EmuTones.onSurface,
                )
            }
            Text(
                text = "ROM Sort only moves files you've already copied to this device " +
                    "into the correct Emulation/roms/<system>/ folders. It never " +
                    "downloads, streams, or distributes any content. All moves require " +
                    "your explicit confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

// ── Scanning state ────────────────────────────────────────────────────────────

@Composable
private fun ScanningContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 3.dp,
            color = EmuTones.onSurface,
        )
        Text(
            text = "Scanning storage for ROM files…",
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurfaceVar,
        )
        Text(
            text = "This may take a few seconds on large storage.",
            style = MaterialTheme.typography.labelMedium,
            color = EmuTones.onSurfaceVar,
        )
    }
}

// ── Results state ─────────────────────────────────────────────────────────────

@Composable
private fun ResultsContent(
    state: RomSortViewModel.ScreenState.Results,
    onMoveOne: (RomSortViewModel.RomEntry) -> Unit,
    onMoveAll: () -> Unit,
    onMoveAssigned: (RomSortViewModel.RomEntry) -> Unit,
    onSkip: (RomSortViewModel.RomEntry) -> Unit,
    onRescan: () -> Unit,
    vm: RomSortViewModel,
) {
    val confidentCount = state.confidentGroups.sumOf { it.entries.size }
    val ambiguousCount = state.needsDecisionEntries.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Summary header ───────────────────────────────────────────────────
        item {
            SummaryHeader(
                totalFound      = state.totalFound,
                confidentCount  = confidentCount,
                ambiguousCount  = ambiguousCount,
                onMoveAll       = onMoveAll,
                onRescan        = onRescan,
            )
        }

        // ── Empty state ──────────────────────────────────────────────────────
        if (state.totalFound == 0) {
            item {
                Text(
                    text = "No ROM files found outside Emulation/. " +
                        "Copy your games to storage and scan again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmuTones.onSurfaceVar,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            return@LazyColumn
        }

        // ── Ready-to-sort groups ──────────────────────────────────────────────
        if (state.confidentGroups.isNotEmpty()) {
            item {
                SectionLabel(
                    icon  = Icons.Outlined.Check,
                    label = "Ready to sort  ($confidentCount)",
                )
            }
            items(state.confidentGroups, key = { it.systemDir }) { group ->
                ConfidentGroupCard(
                    group     = group,
                    onMoveOne = onMoveOne,
                    onMoveAll = { group.entries.forEach(onMoveOne) },
                    onSkip    = onSkip,
                )
            }
        }

        // ── Needs decision ────────────────────────────────────────────────────
        if (state.needsDecisionEntries.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                SectionLabel(
                    icon  = Icons.Outlined.HelpOutline,
                    label = "Needs your choice  ($ambiguousCount)",
                )
                Text(
                    text = "These file extensions match multiple systems — pick one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(state.needsDecisionEntries, key = { it.found.file.absolutePath }) { entry ->
                AmbiguousEntryCard(
                    entry          = entry,
                    onMoveAssigned = { onMoveAssigned(entry) },
                    onSkip         = { onSkip(entry) },
                    vm             = vm,
                )
            }
        }

        // Bottom padding so last item isn't hidden by nav bar.
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Summary header card ───────────────────────────────────────────────────────

@Composable
private fun SummaryHeader(
    totalFound: Int,
    confidentCount: Int,
    ambiguousCount: Int,
    onMoveAll: () -> Unit,
    onRescan: () -> Unit,
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
            Text(
                text = "Found $totalFound ROM file${if (totalFound == 1) "" else "s"} outside Emulation/",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = EmuTones.onSurface,
            )
            if (confidentCount > 0) {
                Text(
                    text = "$confidentCount ready to sort  •  $ambiguousCount need${if (ambiguousCount == 1) "s" else ""} your choice",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }

            if (confidentCount > 0) {
                val moveAllInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = onMoveAll,
                    interactionSource = moveAllInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(moveAllInteraction, cornerRadius = 50.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Move all confident ($confidentCount)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            val rescanInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = onRescan,
                interactionSource = rescanInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusBorder(rescanInteraction, cornerRadius = 8.dp),
            ) {
                Text("Scan again", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ── Section label row ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = EmuTones.onSurfaceVar,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurfaceVar,
        )
    }
}

// ── Confident group card ──────────────────────────────────────────────────────

@Composable
private fun ConfidentGroupCard(
    group: RomSortViewModel.ConfidentGroup,
    onMoveOne: (RomSortViewModel.RomEntry) -> Unit,
    onMoveAll: () -> Unit,
    onSkip: (RomSortViewModel.RomEntry) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.surface),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: system name + target path + "Move all" button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.systemDir.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = EmuTones.onSurface,
                    )
                    Text(
                        text = "→ roms/${group.systemDir}/",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmuTones.onSurfaceVar,
                    )
                }
                if (group.entries.size > 1) {
                    val moveAllInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = onMoveAll,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        interactionSource = moveAllInteraction,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .dpadFocusBorder(moveAllInteraction, cornerRadius = 50.dp),
                    ) {
                        Text(
                            "Move ${group.entries.size}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            HorizontalDivider(color = EmuTones.outlineDivider)

            // Per-file rows.
            for (entry in group.entries) {
                RomFileRow(
                    entry     = entry,
                    onMove    = { onMoveOne(entry) },
                    onSkip    = { onSkip(entry) },
                )
            }
        }
    }
}

// ── Ambiguous entry card ──────────────────────────────────────────────────────

@Composable
private fun AmbiguousEntryCard(
    entry: RomSortViewModel.RomEntry,
    onMoveAssigned: () -> Unit,
    onSkip: () -> Unit,
    vm: RomSortViewModel,
) {
    // The full list of system dirs available for user selection.
    val systemDirs = remember {
        FolderSpec.tree
            .filter { it.startsWith("Emulation/roms/") && it.count { c -> c == '/' } == 2 }
            .map { it.removePrefix("Emulation/roms/") }
            .sorted()
    }

    // Candidates from the classifier (if any), shown first.
    val candidates = when (val cls = entry.found.classification) {
        is RomClassifier.Classification.Ambiguous -> cls.candidates
        else -> emptyList()
    }

    var showPicker by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.surface),
        border = BorderStroke(1.dp, EmuTones.outlineRest),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // File name + size.
            Text(
                text = entry.found.file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = EmuTones.onSurface,
            )
            Text(
                text = formatSize(entry.found.sizeBytes) +
                    if (candidates.isNotEmpty()) "  •  could be: ${candidates.take(3).joinToString(", ")}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = EmuTones.onSurfaceVar,
            )

            // If already assigned, show target + Move/Skip.
            if (entry.assignedDir != null) {
                Text(
                    text = "Assigned to: roms/${entry.assignedDir.name}/",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val moveInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = onMoveAssigned,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        interactionSource = moveInteraction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .dpadFocusBorder(moveInteraction, cornerRadius = 50.dp),
                    ) {
                        Text("Move here", style = MaterialTheme.typography.labelLarge)
                    }
                    val skipInteraction = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = onSkip,
                        interactionSource = skipInteraction,
                        modifier = Modifier
                            .dpadFocusBorder(skipInteraction, cornerRadius = 8.dp),
                    ) {
                        Text("Skip", style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // System picker button.
                    Box {
                        val pickInteraction = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { showPicker = true },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            interactionSource = pickInteraction,
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .dpadFocusBorder(pickInteraction, cornerRadius = 50.dp),
                        ) {
                            Text("Pick system", style = MaterialTheme.typography.labelLarge)
                        }

                        DropdownMenu(
                            expanded = showPicker,
                            onDismissRequest = { showPicker = false },
                        ) {
                            // Show classifier candidates first (highlighted).
                            if (candidates.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Suggested:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmuTones.onSurfaceVar,
                                        )
                                    },
                                    onClick = {},
                                    enabled = false,
                                )
                                for (candidate in candidates) {
                                    DropdownMenuItem(
                                        text = { Text(candidate) },
                                        onClick = {
                                            showPicker = false
                                            vm.assignSystemFromScreen(entry, candidate)
                                        },
                                    )
                                }
                                HorizontalDivider()
                            }

                            // All other system dirs.
                            for (dir in systemDirs.filter { it !in candidates }) {
                                DropdownMenuItem(
                                    text = { Text(dir) },
                                    onClick = {
                                        showPicker = false
                                        vm.assignSystemFromScreen(entry, dir)
                                    },
                                )
                            }
                        }
                    }

                    val skipInteraction = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = onSkip,
                        interactionSource = skipInteraction,
                        modifier = Modifier
                            .dpadFocusBorder(skipInteraction, cornerRadius = 8.dp),
                    ) {
                        Text("Skip", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ── Per-file row ──────────────────────────────────────────────────────────────

@Composable
private fun RomFileRow(
    entry: RomSortViewModel.RomEntry,
    onMove: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.found.file.name,
                style = MaterialTheme.typography.bodySmall,
                color = EmuTones.onSurface,
            )
            Text(
                text = formatSize(entry.found.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = EmuTones.onSurfaceVar,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val moveInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onMove,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                interactionSource = moveInteraction,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .dpadFocusBorder(moveInteraction, cornerRadius = 50.dp),
            ) {
                Text("Move", style = MaterialTheme.typography.labelMedium)
            }
            val skipInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = onSkip,
                contentPadding = PaddingValues(horizontal = 6.dp),
                interactionSource = skipInteraction,
                modifier = Modifier
                    .dpadFocusBorder(skipInteraction, cornerRadius = 8.dp),
            ) {
                Text("Skip", style = MaterialTheme.typography.labelSmall, color = EmuTones.onSurfaceVar)
            }
        }
    }
}

// ── Moving progress ───────────────────────────────────────────────────────────

@Composable
private fun MovingContent(done: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Moving files…",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurface,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
            color = EmuTones.onSurface,
            trackColor = EmuTones.container,
        )
        Text(
            text = "$done / $total",
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurfaceVar,
        )
    }
}

// ── Done state ────────────────────────────────────────────────────────────────

@Composable
private fun DoneContent(
    state: RomSortViewModel.ScreenState.Done,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val doneFocus       = remember { FocusRequester() }
    val doneInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(Unit) {
        try { doneFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Sort complete",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurface,
        )
        Text(
            text = buildString {
                append("Moved ${state.movedCount} file${if (state.movedCount == 1) "" else "s"}")
                if (state.skippedCount > 0) append("  •  skipped ${state.skippedCount}")
                if (state.failedCount > 0)  append("  •  ${state.failedCount} failed")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurface,
        )

        if (state.failures.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = EmuTones.containerHighest),
                border = BorderStroke(1.dp, EmuTones.outlineRest),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Failures",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = EmuTones.onSurface,
                    )
                    for (reason in state.failures) {
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmuTones.onSurfaceVar,
                        )
                    }
                }
            }
        }

        Button(
            onClick = onReset,
            interactionSource = doneInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(doneFocus)
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(doneInteraction, cornerRadius = 50.dp),
        ) {
            Text("Sort more files", style = MaterialTheme.typography.labelLarge)
        }

        val backInteraction = remember { MutableInteractionSource() }
        TextButton(
            onClick = onBack,
            interactionSource = backInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .dpadFocusBorder(backInteraction, cornerRadius = 8.dp),
        ) {
            Text("Back to dashboard", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Error state ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = EmuTones.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurfaceVar,
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
            Text("Try again", style = MaterialTheme.typography.labelLarge)
        }

        val backInteraction = remember { MutableInteractionSource() }
        TextButton(
            onClick = onBack,
            interactionSource = backInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .dpadFocusBorder(backInteraction, cornerRadius = 8.dp),
        ) {
            Text("Back to dashboard", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Utilities ─────────────────────────────────────────────────────────────────

/** Human-readable file size string (B / KB / MB / GB). */
private fun formatSize(bytes: Long): String = when {
    bytes < 1_024L          -> "$bytes B"
    bytes < 1_048_576L      -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824L  -> "${bytes / 1_048_576} MB"
    else                    -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
}
