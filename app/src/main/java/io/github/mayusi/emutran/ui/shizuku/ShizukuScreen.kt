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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                ShizukuAvailability.State.INSTALLED_AND_GRANTED      -> Granted()
                ShizukuAvailability.State.INSTALLED_NEEDS_PERMISSION -> NeedsPermission(vm, ctx)
                ShizukuAvailability.State.NOT_RUNNING                -> NotRunning(vm, install)
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

    // a11y FIX 3: use toggleable instead of clickable so TalkBack announces
    // the card as a single checkbox node with Role.Checkbox and checked state.
    // Inner Checkbox is marked decorative via clearAndSetSemantics so there is
    // only ONE focus stop for TalkBack. Visual and D-pad behaviour are unchanged.
    //
    // a11y FIX 5: title now explicitly mentions "Adreno GPUs" so non-Adreno
    // users understand this option doesn't apply to their device.
    // FLAG for VM owner: ShizukuViewModel.stageGpuDrivers opt-in could also be
    // conditionally hidden/disabled when GpuDetector reports non-Adreno GPU.
    // That requires a new StateFlow in ShizukuViewModel exposing isAdrenoDevice,
    // which is outside this agent's scope (ViewModel not owned this round).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(if (checked) 1.dp else 0.5.dp, borderColor), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value       = checked,
                role        = Role.Checkbox,
                onValueChange = { onCheckedChange(it) },
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Decorative: the card's toggleable node is the single semantics stop.
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text  = "Download Adreno GPU drivers (~30 MB, optional)",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text  = "Adreno GPUs only. Adds custom Turnip/Adreno drivers to " +
                        "Emulation/tools/turnip/ that you can load in " +
                        "Switch and PS Vita emulators (Eden, Yuzu, " +
                        "Vita3K, Skyline) for better performance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
                Text(
                    text  = "Leave this off if you have a non-Adreno GPU, or if " +
                        "you only care about RetroArch, DuckStation, PPSSPP, Dolphin, etc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }
    }
}

@Composable
private fun Granted() {
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
