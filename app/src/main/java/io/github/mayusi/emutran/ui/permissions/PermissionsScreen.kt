package io.github.mayusi.emutran.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Up-front permissions gate. Requests ALL THREE permissions the app needs
 * before the user can proceed to pick / download / install anything:
 *   1. POST_NOTIFICATIONS — runtime permission (Android 13+). Auto-granted on older.
 *   2. MANAGE_EXTERNAL_STORAGE ("All files access") — special access, Settings page.
 *   3. REQUEST_INSTALL_PACKAGES ("Install unknown apps") — special access, Settings page.
 *
 * Continue is disabled until all three are granted. The special-access checks
 * re-run on ON_RESUME so coming back from a Settings page unblocks live.
 */
@Composable
fun PermissionsScreen(
    onContinue: () -> Unit,
    vm: PermissionsViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current

    // --- 1. POST_NOTIFICATIONS (runtime) ---
    fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // permission doesn't exist pre-33 — treat as granted
        }

    var notifGranted by remember { mutableStateOf(notificationsGranted()) }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifGranted = granted }

    // --- 2. All files access & 3. Install unknown apps (special access) ---
    // Re-checked every ON_RESUME so returning from Settings updates the gate.
    val allFilesGranted by rememberOnResume { vm.hasAllFilesAccess() }
    val installGranted by rememberOnResume { vm.canRequestInstalls() }

    val allGranted = notifGranted && allFilesGranted && installGranted

    // Focus requests: point initial focus to the first un-granted button,
    // or to Continue if everything is already granted.
    val notifFocus = remember { FocusRequester() }
    val allFilesFocus = remember { FocusRequester() }
    val installFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(notifGranted, allFilesGranted, installGranted) {
        try {
            when {
                !notifGranted -> notifFocus.requestFocus()
                !allFilesGranted -> allFilesFocus.requestFocus()
                !installGranted -> installFocus.requestFocus()
                else -> continueFocus.requestFocus()
            }
        } catch (_: Exception) { /* No focusable element attached yet — harmless. */ }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        // Step indicator — step 1 of 6.
        StepIndicator(current = 1)

        Text(
            text = stringResource(R.string.perm_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.perm_intro),
            style = MaterialTheme.typography.bodyLarge,
        )

        PermissionRow(
            label       = "Notifications",
            reason      = "Show download progress in the status bar.",
            granted     = notifGranted,
            grantFocusRequester = notifFocus,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        PermissionRow(
            label       = "All files access",
            reason      = "Create the Emulation/ folder tree on your storage.",
            granted     = allFilesGranted,
            grantFocusRequester = allFilesFocus,
            onGrant = { ctx.startActivity(vm.allFilesAccessIntent()) },
        )

        PermissionRow(
            label       = "Install apps",
            reason      = "Install emulators after downloading them.",
            granted     = installGranted,
            grantFocusRequester = installFocus,
            onGrant = { vm.openInstallUnknownAppsSettings() },
        )

        val continueInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onContinue,
            enabled = allGranted,
            interactionSource = continueInteraction,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .focusRequester(continueFocus)
                .dpadFocusBorder(continueInteraction, cornerRadius = 50.dp),
        ) { Text(stringResource(R.string.perm_continue)) }
    }
}

/**
 * One permission row with a two-line layout:
 *   • Bold permission name on top
 *   • Lighter reason/explanation below
 *
 * Right side: a granted check icon when granted, or a "Grant" button that
 * triggers the appropriate request when not yet granted.
 *
 * The card background tints slightly to distinguish granted vs ungranted at
 * a glance:  granted = EmuTones.surface (resting); ungranted = EmuTones.container
 * (slightly brighter) so the actionable row stands out.
 *
 * [grantFocusRequester] allows the parent to request focus on the Grant
 * button when this permission is the first un-granted one on screen.
 */
@Composable
private fun PermissionRow(
    label: String,
    reason: String,
    granted: Boolean,
    grantFocusRequester: FocusRequester = remember { FocusRequester() },
    onGrant: () -> Unit,
) {
    val cardColor = if (granted) EmuTones.surface else EmuTones.container

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text  = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = EmuTones.onSurface,
                )
                Text(
                    text  = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmuTones.onSurfaceVar,
                )
            }
            if (granted) {
                Icon(
                    imageVector     = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.perm_granted),
                    tint            = EmuTones.onSurface,
                    modifier        = Modifier.size(24.dp),
                )
            } else {
                val grantInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick           = onGrant,
                    interactionSource = grantInteraction,
                    border            = BorderStroke(1.dp, Color.White),
                    modifier          = Modifier
                        .heightIn(min = Dimens.ButtonMinHeight)
                        .focusRequester(grantFocusRequester)
                        .dpadFocusBorder(grantInteraction, cornerRadius = 50.dp),
                ) { Text(stringResource(R.string.perm_grant)) }
            }
        }
    }
}
