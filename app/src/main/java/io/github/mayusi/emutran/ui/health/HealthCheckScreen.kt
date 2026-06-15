package io.github.mayusi.emutran.ui.health

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.domain.health.HealthCheckResult
import io.github.mayusi.emutran.domain.health.HealthStatus
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.dpadFocusable
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * "Setup Health" screen — runs a set of read-only checks against the user's
 * emulation setup and reports each as Pass / Warn / Fail / Skipped.
 *
 * Design rules (per project monochrome mandate):
 *  - No hue. Status is conveyed purely through a text glyph (✓ / ! / ✕ / –)
 *    plus a text label. The glyph sits in a small [EmuTones.container] chip so
 *    it is visually distinct from the card body without colour.
 *  - Surfaces follow the EmuTones elevation model: cards at [EmuTones.surface],
 *    focused cards at [EmuTones.containerHigh].
 *  - D-pad: [recheckFocus] (the Re-check button) receives initial focus so the
 *    user can trigger an immediate re-run without touching the screen.
 *
 * Navigation: entered from the Dashboard via [Routes.HEALTH]; Back returns via
 * [onBack] → [navController.popBackStack()].
 */
@Composable
fun HealthCheckScreen(
    onBack: () -> Unit = {},
    vm: HealthCheckViewModel = hiltViewModel(),
) {
    val uiState by vm.state.collectAsStateWithLifecycle()

    // D-pad: Re-check button requests initial focus so the user can instantly
    // trigger a re-run from the hardware D-pad without having to scroll first.
    val recheckFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { recheckFocus.requestFocus() } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Back + title header pinned at the top.
        topBar = {
            Column {
                HealthTopBar(onBack = onBack)
                HorizontalDivider(color = EmuTones.outlineDivider)
            }
        },
        // Re-check button pinned at the bottom — always reachable regardless of
        // scroll position or check count.
        bottomBar = {
            Column {
                HorizontalDivider(color = EmuTones.outlineDivider)
                val recheckInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = vm::recheck,
                    interactionSource = recheckInteraction,
                    enabled = uiState !is HealthCheckViewModel.HealthUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(recheckFocus)
                        .dpadFocusBorder(recheckInteraction, cornerRadius = 50.dp),
                ) {
                    Text("Re-check")
                }
            }
        },
    ) { innerPadding ->
        when (val s = uiState) {
            is HealthCheckViewModel.HealthUiState.Loading -> {
                LoadingContent(innerPadding)
            }
            is HealthCheckViewModel.HealthUiState.Ready -> {
                ReadyContent(
                    results = s.results,
                    innerPadding = innerPadding,
                )
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun HealthTopBar(onBack: () -> Unit) {
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
            text = "Setup Health",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = EmuTones.onSurface,
        )
    }
}

// ── Loading state ─────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(innerPadding: PaddingValues) {
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
                text = "Checking your setup…",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

// ── Ready state ───────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    results: List<HealthCheckResult>,
    innerPadding: PaddingValues,
) {
    // Summary counts — derived from result list.
    val passed  = results.count { it.status == HealthStatus.PASS }
    val warned  = results.count { it.status == HealthStatus.WARN }
    val failed  = results.count { it.status == HealthStatus.FAIL }
    val skipped = results.count { it.status == HealthStatus.SKIPPED }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Summary line — e.g. "3 passed · 1 warning" (skipped omitted if 0).
        item {
            SummaryLine(passed = passed, warned = warned, failed = failed, skipped = skipped)
            Spacer(Modifier.height(4.dp))
        }

        // One card per check result.
        items(results, key = { it.id }) { result ->
            HealthCheckCard(result = result)
        }
    }
}

// ── Summary line ──────────────────────────────────────────────────────────────

@Composable
private fun SummaryLine(passed: Int, warned: Int, failed: Int, skipped: Int) {
    val parts = buildList {
        if (passed  > 0) add("$passed passed")
        if (warned  > 0) add("$warned warning${if (warned  == 1) "" else "s"}")
        if (failed  > 0) add("$failed failed")
        if (skipped > 0) add("$skipped skipped")
    }
    val text = if (parts.isEmpty()) "No checks ran." else parts.joinToString(" · ")

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = EmuTones.onSurfaceVar,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

// ── Result card ───────────────────────────────────────────────────────────────

/**
 * Monochrome card for one health check result.
 *
 * Status is conveyed by:
 *  - A text glyph in a small [EmuTones.container] chip:
 *      PASS    → ✓
 *      WARN    → !
 *      FAIL    → ✕
 *      SKIPPED → –
 *  - A text label beside the glyph ("Passed" / "Warning" / "Failed" / "Skipped")
 *    in [EmuTones.onSurfaceVar] for secondary styling.
 *  - No hue anywhere — the label + glyph fully carry the meaning.
 *
 * D-pad: card is focusable via [dpadFocusable]; focused state switches the
 * container colour from [EmuTones.surface] to [EmuTones.containerHigh].
 */
@Composable
private fun HealthCheckCard(result: HealthCheckResult) {
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
            // Header row: status glyph chip + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusGlyphChip(result.status)
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = EmuTones.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            // Detail line — one-line explanation.
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = EmuTones.onSurfaceVar,
            )
        }
    }
}

/**
 * Small chip that shows a monochrome status glyph + a short label.
 *
 * Layout: [EmuTones.container] background, rounded 8dp corners.
 * Glyph is in [EmuTones.onSurface] (white); label is in [EmuTones.onSurfaceVar]
 * (gray) so the glyph reads first.
 *
 * Glyphs chosen to be unambiguous on a monochrome OLED panel:
 *  PASS    ✓  (check mark — unmistakably good)
 *  WARN    !  (exclamation — attention needed)
 *  FAIL    ✕  (cross — something is broken)
 *  SKIPPED –  (dash — nothing to report)
 */
@Composable
private fun StatusGlyphChip(status: HealthStatus) {
    val (glyph, label) = when (status) {
        HealthStatus.PASS    -> "✓" to "Passed"
        HealthStatus.WARN    -> "!"       to "Warning"
        HealthStatus.FAIL    -> "✕" to "Failed"
        HealthStatus.SKIPPED -> "–" to "Skipped"
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
