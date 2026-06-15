package io.github.mayusi.emutran.ui.pickapps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.github.mayusi.emutran.ui.common.AppInfoDialog
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.SmallStatusPill
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume

@Composable
fun PickAppsScreen(
    onContinue: () -> Unit,
    vm: PickAppsViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    rememberOnResume {
        vm.refreshInstalled()
        true
    }

    when (val s = state) {
        PickAppsViewModel.UiState.Loading -> LoadingBox()
        is PickAppsViewModel.UiState.Failed -> FailedBox(s.message)
        is PickAppsViewModel.UiState.Ready -> ReadyContent(
            allEntries  = s.allEntries,
            picked      = picked,
            installed   = installed,
            filter      = filter,
            search      = search,
            onToggle    = vm::toggle,
            onSetFilter = vm::setFilter,
            onSetSearch = vm::setSearch,
            onRecommended = vm::selectRecommended,
            onNone      = vm::selectNone,
            onContinue  = {
                vm.commit()
                onContinue()
            },
        )
    }
}

@Composable
private fun LoadingBox() {
    Column(
        modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text("Loading emulator list…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedBox(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        Text(
            "Could not load emulator list",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Main picker content, refactored to a Scaffold so the CTA lives in
 * `bottomBar` and the grid always gets full height.  The search field and
 * filter chip row sit in a sticky non-scrolling header above the grid.
 *
 * Fix: "Install 1 apps" → "Install 1 app" (proper singular/plural).
 * Fix: filter chip row is inside a LazyRow with horizontal scroll — chips
 *      remain D-pad reachable via standard focus traversal.
 */
@Composable
private fun ReadyContent(
    allEntries: List<AppEntry>,
    picked: Set<String>,
    installed: Set<String>,
    filter: PickAppsViewModel.PickerFilter,
    search: String,
    onToggle: (String) -> Unit,
    onSetFilter: (PickAppsViewModel.PickerFilter) -> Unit,
    onSetSearch: (String) -> Unit,
    onRecommended: () -> Unit,
    onNone: () -> Unit,
    onContinue: () -> Unit,
) {
    // Category filter first, then the free-text search on top of it.
    // FIX #3: sort is the final expression INSIDE remember() so it only runs
    // when allEntries/filter/search change, not on every recompose.
    val filtered = remember(allEntries, filter, search) {
        val byCategory = filterEntries(allEntries, filter)
        val bySearch = if (search.isBlank()) {
            byCategory
        } else {
            byCategory.filter { it.name.contains(search, ignoreCase = true) }
        }
        bySearch.sortedWith(compareBy { it.name.lowercase() })
    }

    // FIX #20: count is memoised so it doesn't run on every recompose.
    val installedCount = remember(allEntries, installed) {
        allEntries.count { it.id in installed }
    }

    // D-pad: on first composition, request focus on the first grid card (or
    // Continue if the list is empty). This lets the user navigate immediately
    // without touching the screen.
    val firstCardFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(filtered.isEmpty()) {
        try {
            if (filtered.isEmpty()) continueFocus.requestFocus()
            else firstCardFocus.requestFocus()
        } catch (_: Exception) {}
    }

    Scaffold(
        // CTA bar stays pinned at the bottom so the grid always fills the
        // remaining height. contentWindowInsets = none so the scaffold
        // doesn't double-apply navigation-bar padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                HorizontalDivider()
                val continueInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick           = onContinue,
                    enabled           = picked.isNotEmpty(),
                    interactionSource = continueInteraction,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .heightIn(min = 48.dp)
                        .focusRequester(continueFocus)
                        .dpadFocusBorder(continueInteraction, cornerRadius = 50.dp),
                ) {
                    // "Install 1 app" / "Install N apps" — correct plural.
                    Text(
                        if (picked.isEmpty()) "Pick at least one"
                        else "Install ${picked.size} ${if (picked.size == 1) "app" else "apps"}"
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // ── Sticky header: step indicator + title row + search + chips ────
            // Step indicator — step 4 of 6.
            StepIndicator(
                current  = 4,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // Compact one-row header: title + counters + Recommended/None.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text       = "Pick emulators",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text  = buildString {
                            append("${picked.size} selected")
                            if (installedCount > 0) append("  •  $installedCount installed")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val recInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick           = onRecommended,
                        interactionSource = recInteraction,
                        contentPadding    = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier          = Modifier.dpadFocusBorder(recInteraction, cornerRadius = 50.dp),
                    ) { Text("Recommended", style = MaterialTheme.typography.labelMedium) }
                    val noneInteraction = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick           = onNone,
                        interactionSource = noneInteraction,
                        contentPadding    = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier          = Modifier.dpadFocusBorder(noneInteraction, cornerRadius = 50.dp),
                    ) { Text("None", style = MaterialTheme.typography.labelMedium) }
                }
            }

            // Search field — compact, no excess vertical padding.
            androidx.compose.material3.OutlinedTextField(
                value         = search,
                onValueChange = onSetSearch,
                singleLine    = true,
                placeholder   = {
                    Text("Search emulators…", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(
                        imageVector     = Icons.Outlined.Search,
                        contentDescription = "Search",
                        tint            = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { onSetSearch("") }) {
                            Icon(
                                imageVector     = Icons.Outlined.Close,
                                contentDescription = "Clear search",
                                tint            = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // Filter chip row — horizontally scrollable LazyRow so chips are
            // never clipped on the right; each chip is individually focusable
            // for D-pad left/right traversal.
            FilterChipRow(current = filter, onSelect = onSetFilter)

            HorizontalDivider()

            // ── Grid or empty state ──────────────────────────────────────────
            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (search.isNotBlank()) "No matches."
                        else "Nothing in this category.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 4 columns of small cards. Cards are fixed-height so
                // ~4 rows of 4 = 16 cards visible on the Retroid's 1920×1080
                // landscape before scrolling.
                //
                // D-pad navigation: each card is Modifier.focusable() +
                // clickable. The LazyVerticalGrid already handles directional
                // traversal for left/right/up/down between items. The first
                // item gets an explicit FocusRequester so we can land on it
                // when the screen is entered.
                LazyVerticalGrid(
                    columns  = GridCells.Fixed(4),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    verticalArrangement   = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        val isFirst = filtered.first().id == entry.id
                        EntryCard(
                            entry            = entry,
                            checked          = entry.id in picked,
                            alreadyInstalled = entry.id in installed,
                            onClick          = { onToggle(entry.id) },
                            modifier         = if (isFirst) Modifier.focusRequester(firstCardFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    current: PickAppsViewModel.PickerFilter,
    onSelect: (PickAppsViewModel.PickerFilter) -> Unit,
) {
    // Order chosen to put commonly-used filters first.
    data class ChipDef(val label: String, val filter: PickAppsViewModel.PickerFilter)
    val chips = remember {
        buildList {
            add(ChipDef("All",     PickAppsViewModel.PickerFilter.All))
            add(ChipDef("Drivers", PickAppsViewModel.PickerFilter.Drivers))
            // Skip OTHER and DRIVERS from the system list (Drivers is its own chip).
            SystemTag.entries
                .filter { it != SystemTag.OTHER && it != SystemTag.DRIVERS }
                .forEach { add(ChipDef(it.display, PickAppsViewModel.PickerFilter.System(it))) }
            add(ChipDef("Other", PickAppsViewModel.PickerFilter.System(SystemTag.OTHER)))
        }
    }
    // Scrollable row with a little trailing padding so the last chip is
    // fully reachable before it clips into the edge.
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(end = 12.dp),
    ) {
        lazyRowItems(chips, key = { it.label }) { chip ->
            FilterChip(
                selected = chip.filter == current,
                onClick  = { onSelect(chip.filter) },
                label    = { Text(chip.label, style = MaterialTheme.typography.labelMedium) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/**
 * One emulator card in the 4-column grid.
 *
 * D-pad behaviour:
 *  - The card itself is focusable + clickable, so pressing A (DPAD_CENTER)
 *    toggles the checkbox — no raw KeyEvent handling needed.
 *  - The info (i) button is a separate focus stop: D-pad right from the
 *    checkbox area reaches it; pressing A opens the dialog.
 *  - [modifier] is injected so the caller can attach a FocusRequester to
 *    the first card in the list.
 */
@Composable
private fun EntryCard(
    entry: AppEntry,
    checked: Boolean,
    alreadyInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        checked          -> MaterialTheme.colorScheme.primaryContainer
        alreadyInstalled -> MaterialTheme.colorScheme.surfaceVariant
        else             -> MaterialTheme.colorScheme.surface
    }

    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        AppInfoDialog(entry = entry) { showInfo = false }
    }

    // InteractionSource shared between the card's focusable + toggleable so
    // the focus ring reflects the card-level focus (not just the checkbox).
    val cardInteraction = remember { MutableInteractionSource() }
    val cardFocused by cardInteraction.collectIsFocusedAsState()

    // Tiny dense card. heightIn(min=96.dp) so it grows with large font scale
    // instead of clipping text (was fixed height(96.dp)). 4 rows still fit at
    // default font scale.
    //
    // a11y FIX 3: replace separate focusable+clickable with toggleable so that
    // TalkBack sees ONE focus stop announced as "<name>, checkbox, checked/unchecked".
    // The inner Checkbox is marked decorative via clearAndSetSemantics so there's
    // no duplicate focus stop. The info (i) button remains its own focus stop.
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            // toggleable gives TalkBack the Role.Checkbox role and checked state
            // on a single focus stop. D-pad ENTER still toggles selection.
            .toggleable(
                value             = checked,
                role              = Role.Checkbox,
                interactionSource = cardInteraction,
                indication        = null,
                onValueChange     = { onClick() },
            )
            .then(
                if (cardFocused) Modifier.border(
                    border = BorderStroke(2.dp, Color(0xFFFFFFFF)),
                    shape  = RoundedCornerShape(12.dp),
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top row: checkbox + (badge + info icon).
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // a11y: clearAndSetSemantics so the Checkbox has no independent
                // semantics node — the card's toggleable is the single focus stop.
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // FIX #14: use SmallStatusPill (ui/common) instead of the
                    // now-deleted local SmallBadge duplicate.
                    when {
                        alreadyInstalled -> SmallStatusPill(
                            text = "Installed",
                            bg   = MaterialTheme.colorScheme.tertiary,
                            fg   = MaterialTheme.colorScheme.onTertiary,
                        )
                        entry.recommended -> SmallStatusPill(
                            text = "Rec",
                            bg   = MaterialTheme.colorScheme.secondary,
                            fg   = MaterialTheme.colorScheme.onSecondary,
                        )
                        else -> Unit
                    }
                    // Info button remains its own D-pad focus stop (focusable IconButton).
                    // a11y FIX 2: sizeIn enforces 48dp min touch target (was 28dp);
                    // icon stays at 18dp.
                    IconButton(
                        onClick  = { showInfo = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Icon(
                            imageVector     = Icons.Outlined.Info,
                            contentDescription = "About ${entry.name}",
                            tint            = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier        = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // Bottom: name only. Description doesn't fit in this size.
            Text(
                text       = entry.name,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun filterEntries(
    all: List<AppEntry>,
    filter: PickAppsViewModel.PickerFilter,
): List<AppEntry> = when (filter) {
    PickAppsViewModel.PickerFilter.All      -> all
    PickAppsViewModel.PickerFilter.Drivers  -> all.filter { it.system == SystemTag.DRIVERS }
    is PickAppsViewModel.PickerFilter.System -> all.filter { it.system == filter.tag }
}
