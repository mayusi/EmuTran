package io.github.mayusi.emutran.ui.dashboard

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.PendingPackDiff
import io.github.mayusi.emutran.data.update.SelfUpdateResult
import io.github.mayusi.emutran.data.update.UpdateInfo
import io.github.mayusi.emutran.data.update.UpdateProgress
import io.github.mayusi.emutran.ui.common.AppInfoDialog
import io.github.mayusi.emutran.ui.common.CardInstallButton
import io.github.mayusi.emutran.ui.common.CardUninstallButton
import io.github.mayusi.emutran.ui.common.CardUpdateButton
import io.github.mayusi.emutran.ui.common.DISCORD_INVITE_URL
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.EmulatorCard
import io.github.mayusi.emutran.ui.common.SelfUpdateSheet
import io.github.mayusi.emutran.ui.common.SmallStatusPill
import io.github.mayusi.emutran.ui.common.UpdateChip
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume
import io.github.mayusi.emutran.ui.common.truncatedNames
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Real dashboard. Lists every manifest entry as a card, separated into
 * "Installed on this device" (with Update / Uninstall buttons) and
 * "Available to install" (with an Install button).
 *
 * Self-update banner: when [DashboardViewModel.selfUpdate] is Available an
 * inline banner card is inserted above the EmuHelper featured card. Tapping
 * "What's new" opens a [ModalBottomSheet] rendered via the shared
 * [SelfUpdateSheet] composable from ui/common.
 *
 * FIX 5: emuHelperInstalled is now collected from a StateFlow (updated off
 * the main thread by the ViewModel) instead of a synchronous rememberOnResume
 * that ran a blocking PackageManager scan on the composition thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddMore: () -> Unit,
    onRunFullSetup: () -> Unit,
    onAbout: () -> Unit = {},
    onQuickSetup: () -> Unit = {},
    onHealthCheck: () -> Unit = {},
    onProfile: () -> Unit = {},
    onBiosHelper: () -> Unit = {},
    onSortRoms: () -> Unit = {},
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
    val driverHints by vm.driverHints.collectAsStateWithLifecycle()
    val pendingPackDiff by vm.pendingPackDiff.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // FIX 5: emuHelperInstalled collected from StateFlow — no blocking PM call.
    val emuHelperInstalled by vm.emuHelperInstalled.collectAsStateWithLifecycle()

    // Trigger onResume in the ViewModel so it refreshes installed state
    // (including emuHelperInstalled) on every ON_RESUME.
    rememberOnResume { vm.onResume(); true }

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
                driverHints = driverHints,
                pendingPackDiff = pendingPackDiff,
                onInstallEmuHelper = vm::installEmuHelper,
                onAddMore = onAddMore,
                onAbout = onAbout,
                onHealthCheck = onHealthCheck,
                onProfile = onProfile,
                onBiosHelper = onBiosHelper,
                onSortRoms = onSortRoms,
                onCheckForUpdates = vm::checkForUpdates,
                onUpdateAll = vm::updateAll,
                onUpdate = { entry -> vm.updateViaRepository(entry.id) },
                onInstall = vm::downloadAndInstall,
                onUninstall = vm::uninstall,
                onOpenSelfUpdateSheet = vm::openSelfUpdateSheet,
                onDismissSelfUpdateBanner = vm::dismissSelfUpdateBanner,
                onDismissPackDiff = vm::dismissPackDiff,
                innerPadding = innerPadding,
            )
        }
    }

    // ── Self-update "What's new" bottom sheet ──────────────────────────────
    if (selfUpdateSheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // Resolve display params from the current sheet state.
        val sheetVersion = when (val s = selfUpdateSheet) {
            is DashboardViewModel.SelfUpdateSheetUiState.Available    -> s.version
            is DashboardViewModel.SelfUpdateSheetUiState.Downloading  -> s.version
            null -> ""
        }
        val sheetChangelog = when (val s = selfUpdateSheet) {
            is DashboardViewModel.SelfUpdateSheetUiState.Available -> s.changelog
            else -> ""
        }
        val sheetApkUrl = (selfUpdateSheet as? DashboardViewModel.SelfUpdateSheetUiState.Available)?.apkUrl
        val downloadingPercent = (selfUpdateSheet as? DashboardViewModel.SelfUpdateSheetUiState.Downloading)?.percent

        ModalBottomSheet(
            onDismissRequest = vm::dismissSelfUpdateSheet,
            sheetState = sheetState,
            containerColor = EmuTones.containerHighest,
            contentColor = EmuTones.onSurface,
        ) {
            // FIX 4: shared SelfUpdateSheet composable — no more per-screen duplication.
            SelfUpdateSheet(
                version = sheetVersion,
                changelogMarkdown = sheetChangelog,
                downloadingPercent = downloadingPercent,
                onUpdateNow = {
                    sheetApkUrl?.let { vm.startSelfUpdateDownload(it) }
                },
                onSkip = if (sheetVersion.isNotBlank()) {
                    { vm.skipSelfUpdate(sheetVersion) }
                } else null,
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
    driverHints: Map<String, String>,
    pendingPackDiff: PendingPackDiff?,
    onInstallEmuHelper: () -> Unit,
    onAddMore: () -> Unit,
    onAbout: () -> Unit,
    onHealthCheck: () -> Unit,
    onProfile: () -> Unit,
    onBiosHelper: () -> Unit,
    onSortRoms: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onUpdateAll: () -> Unit,
    onUpdate: (AppEntry) -> Unit,
    onInstall: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onOpenSelfUpdateSheet: () -> Unit,
    onDismissSelfUpdateBanner: () -> Unit,
    onDismissPackDiff: () -> Unit,
    innerPadding: PaddingValues,
) {
    val installedEntries = remember(entries, installedIds) { entries.filter { it.id in installedIds } }
    val availableEntries = remember(entries, installedIds) { entries.filter { it.id !in installedIds } }

    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
            // Always-visible "Check for updates" affordance. The hero "Update all"
            // banner only renders when updateCount > 0, so without this the update
            // UI is absent on a normal visit — a chicken-and-egg. This header action
            // is reachable regardless of update count and is D-pad focusable.
            IconButton(onClick = onCheckForUpdates) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Check for updates",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAbout) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About EmuTran",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onHealthCheck) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FactCheck,
                    contentDescription = "Setup Health Check",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onProfile) {
                Icon(
                    imageVector = Icons.Outlined.SettingsBackupRestore,
                    contentDescription = "Backup or restore setup profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onBiosHelper) {
                Icon(
                    imageVector = Icons.Outlined.Memory,
                    contentDescription = "BIOS Helper",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSortRoms) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = "Sort ROMs",
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

        if (updateCount > 0) {
            UpdateHeroBanner(
                updateCount = updateCount,
                onUpdateAll = onUpdateAll,
                onCheckNow = onCheckForUpdates,
            )
            HorizontalDivider(color = EmuTones.outlineDivider)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
        ) {
            if (pendingPackDiff != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    ManifestDiffBanner(
                        diff = pendingPackDiff,
                        onDismiss = onDismissPackDiff,
                    )
                }
            }

            if (selfUpdate != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    SelfUpdateBanner(
                        version = selfUpdate.version,
                        onWhatsNew = onOpenSelfUpdateSheet,
                        onDismiss = onDismissSelfUpdateBanner,
                    )
                }
            }

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
                        driverHint = driverHints[entry.id],
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
                        onInstall = { onInstall(entry) },
                    )
                }
            }

            // Unobtrusive "need help? join Discord" entry at the foot of the
            // list — below the emulator cards so it never disrupts the primary
            // setup/update flow. Full-span, monochrome, matches the banner cards.
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                SupportCard()
            }
        }
    }
}

// ── Community & support card ───────────────────────────────────────────────────

/**
 * Permanent, low-key Discord entry. Mirrors [SelfUpdateBanner]'s monochrome card
 * style (non-dismissible) — an always-available "need help?" affordance with a
 * focusable "Join" action. Opens the shared [DISCORD_INVITE_URL] via
 * [Intent.ACTION_VIEW], the same path the About / update flows use for links.
 */
@Composable
private fun SupportCard() {
    val context = LocalContext.current

    val openDiscord = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = EmuTones.container),
        border = BorderStroke(1.dp, EmuTones.outlineDivider),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = EmuTones.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Need help?",
                    style = MaterialTheme.typography.titleSmall,
                    color = EmuTones.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Get setup help and share configs with the community",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
            Spacer(Modifier.width(4.dp))
            val joinInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = openDiscord,
                interactionSource = joinInteraction,
                modifier = Modifier.dpadFocusBorder(joinInteraction, cornerRadius = 8.dp),
            ) {
                Text(
                    text = "Join Discord",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmuTones.onSurface,
                )
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
        // a11y: sizeIn enforces 48dp min touch target (was 36dp); icon stays at 18dp.
        IconButton(
            onClick = onCheckNow,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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
            // a11y: sizeIn enforces 48dp min touch target (was 32dp); icon stays at 18dp.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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

// ── Manifest "what's new" banner ──────────────────────────────────────────────

/**
 * Dismissible catalog-update banner. Mirrors [SelfUpdateBanner]'s monochrome
 * style. Reads e.g. "Catalog updated — added: Vita3K, Xemu · removed: ARMSX2",
 * truncating long lists to a few names + "+N more". The (x) calls [onDismiss]
 * which clears the pending diff in the ViewModel.
 */
@Composable
private fun ManifestDiffBanner(
    diff: PendingPackDiff,
    onDismiss: () -> Unit,
) {
    val summary = remember(diff) { manifestDiffSummary(diff) }

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
                imageVector = Icons.Outlined.NewReleases,
                contentDescription = null,
                tint = EmuTones.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Catalog updated",
                    style = MaterialTheme.typography.titleSmall,
                    color = EmuTones.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
            Spacer(Modifier.width(4.dp))
            // a11y: sizeIn enforces 48dp min touch target; icon stays at 18dp.
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss catalog update notification",
                    tint = EmuTones.onSurfaceVar,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Build a compact "added: … · removed: …" summary, truncating long lists. */
private fun manifestDiffSummary(diff: PendingPackDiff): String {
    val parts = mutableListOf<String>()
    if (diff.added.isNotEmpty()) parts += "added: " + truncatedNames(diff.added)
    if (diff.removed.isNotEmpty()) parts += "removed: " + truncatedNames(diff.removed)
    return parts.joinToString("  •  ")
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
        colors = CardDefaults.cardColors(containerColor = EmuTones.container),
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
    driverHint: String?,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AppInfoDialog(entry = entry, onDismiss = { showInfo = false })
    }

    val updateButtonLabel = updateInfo?.let { info ->
        if (info.hasUpdate && info.availableVersion != null) {
            "v${info.availableVersion!!.trimStart('v', 'V')}"
        } else null
    }

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
                SmallStatusPill(
                    text = "Installed",
                    bg = MaterialTheme.colorScheme.tertiary,
                    fg = MaterialTheme.colorScheme.onTertiary,
                )
                if (updateInfo?.hasUpdate == true && !inRepositoryProgress) {
                    UpdateChip(text = "Update")
                }
                // a11y: sizeIn enforces 48dp min touch target; icon stays at 18dp.
                IconButton(
                    onClick = { showInfo = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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
            // Subtle per-emulator driver hint (Adreno only). Sits above the
            // action row; absent for non-Adreno GPUs / unknown emulators.
            if (driverHint != null) {
                Text(
                    text = driverHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmuTones.onSurfaceVar,
                )
            }
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
            // a11y: sizeIn enforces 48dp min touch target; icon stays at 18dp.
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
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
