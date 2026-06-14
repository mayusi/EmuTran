package io.github.mayusi.emutran.ui.done

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.BuildConfig
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Post-install summary screen.
 *
 * Task 4 polish:
 *  - Monochrome checkmark hero (Icons.Outlined.CheckCircle, white) as a
 *    reward visual; centered above the content.
 *  - Typography hierarchy aligned to the type scale (headlineMedium → titleMedium
 *    → bodyMedium for the steps).
 *  - Existing primary "Go to Dashboard" button + secondary debug action retained.
 *
 * Primary action is "Go to Dashboard" — this is the primary navigation
 * target after setup finishes. The setup flow is removed from the back
 * stack in EmuTranApp so the user cannot accidentally back into it.
 *
 * [onGoToDashboard] is wired in EmuTranApp with popUpTo(Routes.SPLASH)
 * inclusive=true so the whole setup stack is cleared.
 *
 * FIX 4: [success] controls the hero and title copy. When false (nothing
 * installed, everything failed), the checkmark and celebratory copy are
 * replaced with a neutral warning icon and "Setup didn't complete" header.
 */
@Composable
fun DoneScreen(
    success: Boolean = true,
    onGoToDashboard: () -> Unit = {},
    onTestInstall: () -> Unit = {},
) {
    // D-pad: land on the primary action (Go to Dashboard) immediately.
    val dashboardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { dashboardFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        // ── Hero icon — checkmark on success, warning on failure ───────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (success) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Setup complete",
                    tint = EmuTones.onSurface,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Setup did not complete",
                    tint = EmuTones.onSurfaceVar,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        // ── Title ──────────────────────────────────────────────────────────
        Text(
            text = if (success) stringResource(R.string.done_title)
                   else "Setup didn't complete",
            style = MaterialTheme.typography.headlineMedium,
            color = if (success) MaterialTheme.colorScheme.primary
                    else EmuTones.onSurfaceVar,
        )

        // ── Next-steps or failure explanation ─────────────────────────────
        if (success) {
            Text(
                text = stringResource(R.string.done_next_steps),
                style = MaterialTheme.typography.titleMedium,
                color = EmuTones.onSurface,
            )
            Text(
                text = "• " + stringResource(R.string.done_step_bios),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
            Text(
                text = "• " + stringResource(R.string.done_step_roms),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
            Text(
                text = "• " + stringResource(R.string.done_step_configure),
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        } else {
            Text(
                text = "No apps were installed this run. You can retry " +
                    "from the dashboard or re-run Quick Setup after " +
                    "checking your internet connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurfaceVar,
            )
        }

        // ── Primary action ─────────────────────────────────────────────────
        // Back-stack is cleared by the NavHost popUpTo call in EmuTranApp
        // so the user cannot go back to setup.
        val dashboardInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onGoToDashboard,
            interactionSource = dashboardInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .focusRequester(dashboardFocus)
                .dpadFocusBorder(dashboardInteraction, cornerRadius = 50.dp),
        ) { Text("Go to Dashboard") }

        // ── Debug-only install pipeline test ──────────────────────────────
        // Hidden in release APKs so end users never see it. Kept around because
        // it's a quick way to validate the install path in isolation when
        // something downstream of the picker breaks.
        if (BuildConfig.DEBUG) {
            val devInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = onTestInstall,
                interactionSource = devInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonMinHeight)
                    .dpadFocusBorder(devInteraction, cornerRadius = 50.dp),
            ) { Text("Dev: test download + install") }
        }
    }
}
