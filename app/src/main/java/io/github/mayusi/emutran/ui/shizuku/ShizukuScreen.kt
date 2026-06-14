package io.github.mayusi.emutran.ui.shizuku

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.domain.install.ShizukuAvailability
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Three-state Shizuku setup screen between picker and progress.
 *
 *   NOT_RUNNING                → 'Install Shizuku for me' button auto-
 *                                downloads + installs the latest Shizuku
 *                                release. Plus inline activation steps
 *                                for after the install completes.
 *   INSTALLED_NEEDS_PERMISSION → 'Grant Shizuku permission' triggers the
 *                                in-app permission dialog.
 *   INSTALLED_AND_GRANTED      → green checkmark; install loop will run
 *                                silently.
 *
 * 'Start install' is always visible in a pinned bottomBar (Scaffold) so
 * the user never has to scroll past the GPU drivers card to confirm.
 *
 * Title changed from the abstract 'Install mode' to 'Almost ready' —
 * clearer context at step 5 of 6.
 */
@Composable
fun ShizukuScreen(
    onContinue: () -> Unit,
    vm: ShizukuViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val install by vm.installFlow.collectAsStateWithLifecycle()
    val stageDrivers by vm.stageGpuDrivers.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    rememberOnResume { vm.refresh(); true }

    // D-pad: land on the Start Install button immediately so the user can
    // confirm + continue in one press.
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { startFocus.requestFocus() } catch (_: Exception) {}
    }

    Scaffold(
        // 'Start install' pinned at the bottom — always above the fold.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Column {
                HorizontalDivider()
                val startInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick           = onContinue,
                    interactionSource = startInteraction,
                    modifier          = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(startFocus)
                        .dpadFocusBorder(startInteraction, cornerRadius = 50.dp),
                ) { Text("Start install") }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
        ) {
            // Step indicator — step 5 of 6.
            StepIndicator(current = 5)

            Text(
                text  = "Almost ready",
                style = MaterialTheme.typography.headlineMedium,
            )

            when (state) {
                ShizukuAvailability.State.INSTALLED_AND_GRANTED      -> Granted(onContinue = {})
                ShizukuAvailability.State.INSTALLED_NEEDS_PERMISSION -> NeedsPermission(vm, onContinue = {}, ctx)
                ShizukuAvailability.State.NOT_RUNNING                -> NotRunning(vm, install, onContinue = {})
            }

            // Extra options section, always shown regardless of Shizuku state.
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text  = "Extras",
                style = MaterialTheme.typography.titleMedium,
            )
            GpuDriversOptIn(
                checked        = stageDrivers,
                onCheckedChange = vm::setStageGpuDrivers,
            )
        }
    }
}

/**
 * GPU drivers opt-in card.
 *
 * Checked state is communicated via:
 *   • EmuTones.containerHigh fill (elevated vs surface when unchecked)
 *   • 1 dp white border drawn around the card (clear selection signal)
 * This avoids any hue while remaining easy to read at a glance.
 */
@Composable
private fun GpuDriversOptIn(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val containerColor = if (checked) EmuTones.containerHigh else EmuTones.surface
    val borderColor    = if (checked) Color.White else EmuTones.outlineRest

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(if (checked) 1.dp else 0.5.dp, borderColor), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text  = "Download GPU drivers (~30 MB, optional)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text  = "Adds custom Adreno drivers to " +
                        "Emulation/tools/turnip/ that you can load in " +
                        "Switch and PS Vita emulators (Eden, Yuzu, " +
                        "Vita3K, Skyline) for better performance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
                Text(
                    text  = "Leave this off if you only care about " +
                        "RetroArch, DuckStation, PPSSPP, Dolphin, etc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }
    }
}

@Composable
private fun Granted(onContinue: () -> Unit) {
    Text(
        text  = "Shizuku is set up — installs will run silently.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.tertiary,
    )
    Text(
        text  = "All your selected apps will install with no Install " +
            "dialogs to tap.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun NeedsPermission(
    vm: ShizukuViewModel,
    onContinue: () -> Unit,
    ctx: android.content.Context,
) {
    Text(
        text  = "Shizuku is running — just needs your permission.",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text  = "Tap below, then Allow when Shizuku asks. After that, " +
            "every install runs silently.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick           = { vm.requestPermission() },
        interactionSource = interaction,
        modifier          = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ButtonMinHeight)
            .dpadFocusBorder(interaction, cornerRadius = 50.dp),
    ) { Text("Grant Shizuku permission") }
}

@Composable
private fun NotRunning(
    vm: ShizukuViewModel,
    install: ShizukuViewModel.InstallFlow,
    onContinue: () -> Unit,
) {
    Text(
        text  = "Shizuku not detected.",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text  = "Shizuku lets EmuTran install every emulator silently — " +
            "no Install dialogs at all. Recommended, but optional.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text  = "Without it: Android shows an Install dialog for each app. " +
            "You tap Install each time. Works fine, just slower.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    InstallFlowSection(install)

    val shizukuInstallInteraction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick           = vm::autoInstallShizuku,
        enabled           = install !is ShizukuViewModel.InstallFlow.Downloading &&
            install !is ShizukuViewModel.InstallFlow.Installing,
        interactionSource = shizukuInstallInteraction,
        modifier          = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.ButtonMinHeight)
            .dpadFocusBorder(shizukuInstallInteraction, cornerRadius = 50.dp),
    ) { Text("Install Shizuku for me") }

    // After install succeeds, we surface activation steps. The user
    // has to do this part manually — there is no app-driven way to
    // start Shizuku, that's an Android security boundary.
    if (install is ShizukuViewModel.InstallFlow.InstalledOk) {
        ActivationSteps()
    }
}

@Composable
private fun InstallFlowSection(install: ShizukuViewModel.InstallFlow) {
    when (install) {
        ShizukuViewModel.InstallFlow.Idle -> Unit
        is ShizukuViewModel.InstallFlow.Downloading -> {
            val pct = if (install.total > 0)
                install.downloaded.toFloat() / install.total else 0f
            Text(
                text  = "Downloading Shizuku…",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ShizukuViewModel.InstallFlow.Installing -> {
            Text(
                text  = "Tap Install when prompted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ShizukuViewModel.InstallFlow.InstalledOk -> {
            Text(
                text  = "Shizuku installed.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        ShizukuViewModel.InstallFlow.Cancelled -> {
            Text(
                text  = "Install was cancelled. Tap again to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ShizukuViewModel.InstallFlow.Failed -> {
            Text(
                text  = "Failed: ${install.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ActivationSteps() {
    Spacer(Modifier.size(8.dp))
    Text(
        text  = "Now activate Shizuku:",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text  = "Shizuku is installed but not yet running. Activate it " +
            "using ONE of these methods, then come back here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        text  = "Easiest — wireless ADB (Android 11+):",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    NumberedSteps(
        listOf(
            "Open Settings → System → Developer options → Wireless debugging → On.",
            "Tap 'Pair device with pairing code', note the IP+port and 6-digit code.",
            "Open the Shizuku app → use the pairing code section → enter both.",
            "After pairing, tap 'Start' inside Shizuku.",
            "Come back here. Detection is automatic.",
        )
    )

    Text(
        text  = "Or — with a PC over USB (no Wi-Fi needed):",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    NumberedSteps(
        listOf(
            "Plug the device into your PC with USB debugging on.",
            "On the PC, run: adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            "Come back here. Detection is automatic.",
        )
    )

    Text(
        text  = "Heads-up: Shizuku stops after a reboot (Android security). " +
            "You'll need to redo the activation step each time the device " +
            "restarts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NumberedSteps(steps: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, s ->
            Text(
                text  = "${i + 1}. $s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
