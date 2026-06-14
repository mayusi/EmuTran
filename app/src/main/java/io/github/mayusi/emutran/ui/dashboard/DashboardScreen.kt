package io.github.mayusi.emutran.ui.dashboard

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.data.update.UpdateInfo
import io.github.mayusi.emutran.data.update.UpdateProgress
import io.github.mayusi.emutran.ui.common.AppInfoDialog
import io.github.mayusi.emutran.ui.common.CardInstallButton
import io.github.mayusi.emutran.ui.common.CardUninstallButton
import io.github.mayusi.emutran.ui.common.CardUpdateButton
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.EmulatorCard
import io.github.mayusi.emutran.ui.common.MarkdownText
import io.github.mayusi.emutran.ui.common.SmallStatusPill
import io.github.mayusi.emutran.ui.common.UpdateChip
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Real dashboard. Lists every manifest entry as a card, separated into
 * "Installed on this device" (with Update / Uninstall buttons) and
 * "Available to install" (with an Install button).
 *
 * Task 1 fix: Quick Setup button + Re-run TextButton now live inside the
 * Scaffold's `bottomBar` slot, not floating over the grid. The grid
 * receives the Scaffold's innerPadding so it scrolls correctly.
 *
 * Task 2: cards use the new [EmulatorCard] composable with animated D-pad
 * focus (scale spring + border change). Uninstall is ghost/TextButton style.
 *
 * Task 3: update count header + per-card "Update vX" chip sourced from
 * [UpdateRepository] via [DashboardViewModel.updateState].
 *
 * Self-update banner: when [DashboardViewModel.selfUpdate] is Available an
 * inline banner card is inserted above the EmuHelper featured card. Tapping
 * "What's new" opens a [ModalBottomSheet] with the release notes (rendered
 * via [MarkdownText]), a download progress bar, and "Update now" / "Skip this
 * version" / "Not now" actions. Cancel cancels the in-flight download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddMore: () -> Unit,
    onRunFullSetup: () -> Unit,
    onAbout: () -> Unit = {},
    onQuickSetup: () -> Unit = {},
    onHealthCheck: () -> Unit = {},
    vm: DashboardViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val emuHelperInstalling by vm.emuHelperInstalling.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val updateCount by vm.updateCount.collectAsStateWithLifecycle()
    val updateProgressMap by vm.updateProgressMap.collectAsStateWithLifecycle()
    val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
    val selfUpdateSheet by vm.selfUpdateSheet.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    rememberOnResume { vm.refresh(); true }
    val emuHelperInstalled by rememberOnResume { vm.isEmuHelperInstalled() }

    LaunchedEffect(Unit) {
        vm.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // D-pad: Quick Setup button gets initial focus.
    val quickFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { quickFocus.requestFocus() } catch (_: Exception) {}
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = EmuTones.containerHighest,
                    contentColor = EmuTones.onSurface,
                )
            }
        },
        // TASK 1 FIX: bottom CTAs live in the Scaffold's bottomBar slot
        // so they never float over the grid during scroll.
        bottomBar = {
            Column {
                HorizontalDivider(color = EmuTones.outlineDivider)
                val quickInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = onQuickSetup,
                    interactionSource = quickInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(quickFocus)
                        .dpadFocusBorder(quickInteraction, cornerRadius = 50.dp),
                ) { Text("Quick Setup (recommended)") }

                val setupInteraction = remember { MutableInteractionSource() }
                TextButton(
                    onClick = onRunFullSetup,
                    interactionSource = setupInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .dpadFocusBorder(setupInteraction, cornerRadius = 8.dp),
                ) { Text("Re-run full setup (permissions, folder, etc.)") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // innerPadding accounts for the bottomBar height, so the grid
        // content never hides behind the CTA buttons.
        when (val s = state) {
            DashboardViewModel.UiState.Loading -> Loading(innerPadding)
            is DashboardViewModel.UiState.Failed -> Failed(s.message, innerPadding, onRetry = vm::refresh)
            is DashboardViewModel.UiState.Ready -> Ready(
                entries = s.entries,
                installedIds = s.installedIds,
                busy = busy,
                emuHelper = vm.emuHelper,
                emuHelperInstalled = emuHelperInstalled,
                emuHelperInstalling = emuHelperInstalling,
                updateState = updateState,
                updateCount = updateCount,
                updateProgressMap = updateProgressMap,
                selfUpdate = selfUpdate as? SelfUpdateResult.Available,
                onInstallEmuHelper = vm::installEmuHelper,
                onAddMore = onAddMore,
                onAbout = onAbout,
                onHealthCheck = onHealthCheck,
                onCheckForUpdates = vm::checkForUpdates,
                onUpdateAll = vm::updateAll,
                // FIX 3: installed-card update goes through the repository so the badge clears.
                onUpdate = { entry -> vm.updateViaRepository(entry.id) },
                // FIX 4: available-card install uses the renamed downloadAndInstall.
                onInstall = vm::downloadAndInstall,
                onUninstall = vm::uninstall,
                onOpenSelfUpdateSheet = vm::openSelfUpdateSheet,
                onDismissSelfUpdateBanner = vm::dismissSelfUpdateBanner,
                innerPadding = innerPadding,
            )
        }
    }

    // ── Self-update "What's new" bottom sheet ──────────────────────────────
    // Rendered outside the Scaffold so it overlays the full screen correctly.
    if (selfUpdateSheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = vm::dismissSelfUpdateSheet,
            sheetState = sheetState,
            containerColor = EmuTones.containerHighest,
            contentColor = EmuTones.onSurface,
        ) {
            DashboardSelfUpdateSheetContent(
                available = selfUpdateSheet as? DashboardViewModel.SelfUpdateSheetUiState.Available,
                downloading = selfUpdateSheet as? DashboardViewModel.SelfUpdateSheetUiState.Downloading,
                onUpdateNow = { url -> vm.startSelfUpdateDownload(url) },
                onSkip = { version -> vm.skipSelfUpdate(version) },
                onDismiss = vm::dismissSelfUpdateSheet,
            )
        }
    }
}

@Composable
private fun Loading(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Failed(message: String, innerPadding: PaddingValues, onRetry: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        Text(
            text = "Could not load dashboard",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium)
        // FIX 3: Failed state must not be a dead-end — provide a retry affordance.
        val retryInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onRetry,
            interactionSource = retryInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(retryInteraction, cornerRadius = 50.dp),
        ) { Text("Try again") }
    }
}

@Composable
private fun Ready(
    entries: List<AppEntry>,
    installedIds: Set<String>,
    busy: Set<String>,
    emuHelper: AppEntry,
    emuHelperInstalled: Boolean,
    emuHelperInstalling: Boolean,
    updateState: Map<String, UpdateInfo>,
    updateCount: Int,
    updateProgressMap: Map<String, UpdateProgress>,
    selfUpdate: SelfUpdateResult.Available?,
    onInstallEmuHelper: () -> Unit,
    onAddMore: () -> Unit,
    onAbout: () -> Unit,
    onHealthCheck: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onUpdateAll: () -> Unit,
    onUpdate: (AppEntry) -> Unit,          // installed-card update (repository-driven, clears badge)
    onInstall: (AppEntry) -> Unit,         // available-card install (direct download path)
    onUninstall: (AppEntry) -> Unit,
    onOpenSelfUpdateSheet: () -> Unit,
    onDismissSelfUpdateBanner: () -> Unit,
    innerPadding: PaddingValues,
) {
    val installedEntries = entries.filter { it.id in installedIds }
    val availableEntries = entries.filter { it.id !in installedIds }

    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // Compact one-row header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "EmuTran",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "${installedEntries.size} installed  •  ${availableEntries.size} available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAbout) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About EmuTran",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Health Check icon button — lets the user verify their setup at any time.
            IconButton(onClick = onHealthCheck) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FactCheck,
                    contentDescription = "Setup Health Check",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            val addMoreInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = onAddMore,
                interactionSource = addMoreInteraction,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier.dpadFocusBorder(addMoreInteraction, cornerRadius = 50.dp),
            ) { Text("Add more", style = MaterialTheme.typography.labelLarge) }
        }

        HorizontalDivider(color = EmuTones.outlineDivider)

        // Update-count hero: only visible when at least one update is available.
        if (updateCount > 0) {
            UpdateHeroBanner(
                updateCount = updateCount,
                onUpdateAll = onUpdateAll,
                onCheckNow = onCheckForUpdates,
            )
            HorizontalDivider(color = EmuTones.outlineDivider)
        }

        // Grid — receives innerPadding so it scrolls correctly above the bottom bar.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 8.dp,
            ),
        ) {
            // Self-update banner — shown above the EmuHelper card when a new
            // EmuTran release is available (and not dismissed this session).
            if (selfUpdate != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    SelfUpdateBanner(
                        version = selfUpdate.version,
                        onWhatsNew = onOpenSelfUpdateSheet,
                        onDismiss = onDismissSelfUpdateBanner,
                    )
                }
            }

            // Featured EmuHelper card pinned to the very top.
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                EmuHelperCard(
                    entry = emuHelper,
                    installed = emuHelperInstalled,
                    installing = emuHelperInstalling,
                    onInstall = onInstallEmuHelper,
                )
            }
            if (installedEntries.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    SectionLabel("Installed on this device")
                }
                items(installedEntries, key = { "i-${it.id}" }) { entry ->
                    InstalledCard(
                        entry = entry,
                        busy = entry.id in busy,
                        updateInfo = updateState[entry.id],
                        updateProgress = updateProgressMap[entry.id],
                        // FIX 3: use repository-driven path so the update badge is
                        // cleared from persisted state after a successful update.
                        onUpdate = { onUpdate(entry) },
                        onUninstall = { onUninstall(entry) },
                    )
                }
            }
            if (availableEntries.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    SectionLabel("Available to install")
                }
                items(availableEntries, key = { "a-${it.id}" }) { entry ->
                    AvailableCard(
                        entry = entry,
                        busy = entry.id in busy,
                        // FIX 4: AvailableCard "Install" uses the direct download path
                        // (no persisted badge to clear for a not-yet-installed app).
                        onInstall = { onInstall(entry) },
                    )
                }
            }
        }
    }
}

// ── Update hero banner ───────────────────────────────────────────────────────

@Composable
private fun UpdateHeroBanner(
    updateCount: Int,
    onUpdateAll: () -> Unit,
    onCheckNow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = EmuTones.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$updateCount update${if (updateCount == 1) "" else "s"} available",
                style = MaterialTheme.typography.titleSmall,
                color = EmuTones.onSurface,
            )
        }
        // "Check now" — dim icon button
        IconButton(
            onClick = onCheckNow,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Check for updates",
                tint = EmuTones.onSurfaceVar,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        val updateAllInteraction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onUpdateAll,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.dpadFocusBorder(updateAllInteraction, cornerRadius = 50.dp),
            interactionSource = updateAllInteraction,
        ) {
            Text("Update all", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Self-update banner ────────────────────────────────────────────────────────

/**
 * Inline dismissible card shown above the EmuHelper featured card when a new
 * EmuTran release is available. Monochrome: uses [EmuTones.containerHigh] as
 * the card surface so it sits one elevation step above the page background,
 * giving it visual prominence without any hue.
 *
 * D-pad: "What's new" button has focus priority; the dismiss icon is also
 * focusable. Both actions call their respective lambdas and do NOT navigate
 * away.
 */
@Composable
private fun SelfUpdateBanner(
    version: String,
    onWhatsNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.containerHigh),
        border = BorderStroke(1.dp, EmuTones.outlineDivider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = EmuTones.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Update available — v$version",
                    style = MaterialTheme.typography.titleSmall,
                    color = EmuTones.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "New EmuTran release ready to install",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
            Spacer(Modifier.width(4.dp))
            // "What's new" — opens the patch-notes sheet.
            val whatsNewInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = onWhatsNew,
                interactionSource = whatsNewInteraction,
                modifier = Modifier.dpadFocusBorder(whatsNewInteraction, cornerRadius = 8.dp),
            ) {
                Text(
                    text = "What's new",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmuTones.onSurface,
                )
            }
            // Dismiss (x) — session-only hide, does NOT persist skip.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss update notification",
                    tint = EmuTones.onSurfaceVar,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Self-update bottom sheet ──────────────────────────────────────────────────

/**
 * Patch-notes + install sheet for the dashboard self-update flow.
 *
 * Layout mirrors [AboutScreen]'s SelfUpdateSheetContent but is self-contained
 * here — no shared composable in ui/common so the About screen's copy is
 * untouched. The changelog is rendered via [MarkdownText] for proper
 * headers/bullets/bold/link support.
 *
 * Download cancellation: the owning [DashboardViewModel] stores the download
 * [Job] and cancels it when [onDismiss] is called via [dismissSelfUpdateSheet],
 * so the system installer is never launched after the user closes the sheet.
 */
@Composable
private fun DashboardSelfUpdateSheetContent(
    available  : DashboardViewModel.SelfUpdateSheetUiState.Available?,
    downloading: DashboardViewModel.SelfUpdateSheetUiState.Downloading?,
    onUpdateNow: (apkUrl: String) -> Unit,
    onSkip     : (version: String) -> Unit,
    onDismiss  : () -> Unit,
) {
    val version  = available?.version  ?: ""
    val changelog = available?.changelog ?: ""
    val apkUrl   = available?.apkUrl

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "What's new in v$version",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )

        if (changelog.isNotBlank()) {
            // Render release notes with MarkdownText — headers/bullets/links
            // in GitHub release bodies display correctly.
            MarkdownText(
                markdown = changelog,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Download progress bar — shown when in Downloading state.
        if (downloading != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { downloading.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = EmuTones.onSurface,
                    trackColor = EmuTones.outlineDivider,
                )
                Text(
                    text = "Downloading… ${downloading.percent}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }

        // Action buttons row.
        if (available != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val updateInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = { onUpdateNow(available.apkUrl) },
                    interactionSource = updateInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(updateInteraction, cornerRadius = 50.dp),
                ) { Text("Update now") }

                val skipInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { onSkip(available.version) },
                    interactionSource = skipInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .dpadFocusBorder(skipInteraction, cornerRadius = 50.dp),
                ) { Text("Skip version") }
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
                    "Not now",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }

        // While downloading, only show a "Downloading…" disabled button.
        if (downloading != null) {
            val cancelInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onDismiss,
                enabled = false,
                interactionSource = cancelInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonMinHeight)
                    .dpadFocusBorder(cancelInteraction, cornerRadius = 50.dp),
            ) { Text("Downloading…") }
        }
    }
}

// ── Card variants ────────────────────────────────────────────────────────────

@Composable
private fun EmuHelperCard(
    entry: AppEntry,
    installed: Boolean,
    installing: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = EmuTones.container,
        ),
        border = BorderStroke(1.dp, EmuTones.outlineDivider),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                SmallStatusPill(
                    text = "Recommended",
                    bg = MaterialTheme.colorScheme.primary,
                    fg = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(6.dp))
                SmallStatusPill(
                    text = "by ${entry.author}",
                    bg = MaterialTheme.colorScheme.secondaryContainer,
                    fg = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = entry.about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                installed -> {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Installed", style = MaterialTheme.typography.labelLarge) }
                }
                installing -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Installing…", style = MaterialTheme.typography.labelLarge)
                    }
                }
                else -> {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Install", style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
}

@Composable
private fun InstalledCard(
    entry: AppEntry,
    busy: Boolean,
    updateInfo: UpdateInfo?,
    updateProgress: UpdateProgress?,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AppInfoDialog(entry = entry, onDismiss = { showInfo = false })
    }

    // Determine the label for the Update button:
    // If there's a newer version tag, show "Update v1.2" style text.
    val updateButtonLabel = updateInfo?.let { info ->
        if (info.hasUpdate && info.availableVersion != null) {
            "v${info.availableVersion!!.trimStart('v', 'V')}"
        } else null
    }

    // Per-card progress from the repository update path.
    val inRepositoryProgress = updateProgress != null &&
        updateProgress !is UpdateProgress.Done &&
        updateProgress !is UpdateProgress.Cancelled

    EmulatorCard(
        name = entry.name,
        secondaryLine = entry.system.display,
        onClick = { showInfo = true },
        topEndBadge = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // "Installed" badge — near-white tertiary pill (tertiary = #D0D0D0 bg).
                SmallStatusPill(
                    text = "Installed",
                    bg = MaterialTheme.colorScheme.tertiary,
                    fg = MaterialTheme.colorScheme.onTertiary,
                )
                // Update badge: outlined chip shown only when an update is available
                // and no in-flight progress for this entry.
                if (updateInfo?.hasUpdate == true && !inRepositoryProgress) {
                    UpdateChip(text = "Update")
                }
                // Info button
                IconButton(
                    onClick = { showInfo = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About ${entry.name}",
                        tint = EmuTones.onSurfaceVar,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        bottomContent = {
            // Show progress text if repository-driven update is in flight.
            if (inRepositoryProgress) {
                Text(
                    text = when (updateProgress) {
                        is UpdateProgress.Resolving -> "Resolving…"
                        is UpdateProgress.Downloading -> {
                            val pct = if (updateProgress.totalBytes > 0L) {
                                (updateProgress.downloaded * 100 / updateProgress.totalBytes).toInt()
                            } else 0
                            "Downloading… $pct%"
                        }
                        is UpdateProgress.Installing -> "Installing…"
                        else -> "Updating…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurfaceVar,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CardUpdateButton(
                        busy = busy,
                        onClick = onUpdate,
                        updateLabel = updateButtonLabel,
                        modifier = Modifier.weight(1f),
                    )
                    CardUninstallButton(
                        busy = busy,
                        onClick = onUninstall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
private fun AvailableCard(
    entry: AppEntry,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AppInfoDialog(entry = entry, onDismiss = { showInfo = false })
    }

    EmulatorCard(
        name = entry.name,
        secondaryLine = entry.system.display,
        onClick = { showInfo = true },
        topEndBadge = {
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About ${entry.name}",
                    tint = EmuTones.onSurfaceVar,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        bottomContent = {
            CardInstallButton(
                busy = busy,
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
    )
}
