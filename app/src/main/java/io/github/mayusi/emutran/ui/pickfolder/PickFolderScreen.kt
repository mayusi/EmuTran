package io.github.mayusi.emutran.ui.pickfolder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.data.storage.StorageVolumes
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.rememberOnResume
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * Folder selection. Offers quick-select rows for each detected storage
 * volume (internal storage / SD card) so users on a D-pad handheld can
 * change the storage root with a single button press.
 *
 * A manual text-field fallback remains for users who want a custom path.
 *
 * Why this is better than SAF for our use case:
 *  - SAF picker on Odin (and similar OEM builds) is too restrictive.
 *  - Plain text path: zero clicks if the user accepts the default.
 *  - Other emulators using MANAGE_EXTERNAL_STORAGE already trained the
 *    user to accept that permission grant once and forget.
 *
 * Button order: PRIMARY "Use this folder" is last/bottom (most prominent);
 * SECONDARY "Reset to default" is above it so the D-pad landing order
 * stays logical (continue = last stop).
 */
@Composable
fun PickFolderScreen(
    onContinue: () -> Unit,
    vm: PickFolderViewModel = hiltViewModel(),
) {
    val chosen by vm.chosen.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // All-files access is requested up front in the Permissions step, so by
    // the time we get here it's already granted. We still re-check on
    // ON_RESUME as a defensive fallback and surface a recovery path if, for
    // some reason, the grant was revoked between then and now.
    val hasPermission by rememberOnResume { vm.hasPermission() }

    // D-pad: if volumes are available, start focus on the first volume row;
    // otherwise fall through to the text field, then Continue.
    val firstVolumeFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(volumes.isEmpty()) {
        try {
            if (volumes.isEmpty()) continueFocus.requestFocus()
            else firstVolumeFocus.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        // Step indicator — step 3 of 6.
        StepIndicator(current = 3)

        Text(
            text  = stringResource(R.string.folder_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text  = "EmuTran will create the Emulation/ tree at the path " +
                "below. The default works for most people. Change it if " +
                "you want it on your SD card or elsewhere.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!hasPermission) {
            Text(
                text  = "First we need permission to manage files. Tap below, " +
                    "toggle \"Allow access to manage all files\" on, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            val permInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick           = { ctx.startActivity(vm.requestPermissionIntent()) },
                interactionSource = permInteraction,
                modifier          = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonMinHeight)
                    .dpadFocusBorder(permInteraction, cornerRadius = 50.dp),
            ) { Text("Grant All Files permission") }
        }

        // ── Volume quick-select rows ─────────────────────────────────────────
        // Shown when at least one volume could be detected. The currently
        // chosen path drives the radio-button selection — if the user typed a
        // custom path, none of the rows will be selected.
        if (volumes.isNotEmpty()) {
            Text(
                text       = "Storage location",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            volumes.forEachIndexed { index, volume ->
                val volumePath = if (volume.path.endsWith("/Emulation"))
                    volume.path else "${volume.path.trimEnd('/')}/Emulation"
                val isSelected = chosen == volumePath
                val isFirst = index == 0

                VolumeRow(
                    volume   = volume,
                    selected = isSelected,
                    enabled  = hasPermission,
                    onSelect = { vm.selectVolume(volume) },
                    modifier = if (isFirst) Modifier.focusRequester(firstVolumeFocus) else Modifier,
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
        }

        // ── Manual path text field ───────────────────────────────────────────
        OutlinedTextField(
            value         = chosen,
            onValueChange = vm::setPath,
            label         = { Text("Storage root (custom path)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            enabled       = hasPermission,
        )

        // Secondary action first, primary action last — standard mobile CTA order.
        val resetInteraction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick           = { vm.setPath(vm.defaultPath) },
            enabled           = hasPermission,
            interactionSource = resetInteraction,
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .dpadFocusBorder(resetInteraction, cornerRadius = 50.dp),
        ) { Text("Reset to default") }

        val continueInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = {
                vm.commit()
                onContinue()
            },
            enabled           = hasPermission && chosen.isNotBlank(),
            interactionSource = continueInteraction,
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .focusRequester(continueFocus)
                .dpadFocusBorder(continueInteraction, cornerRadius = 50.dp),
        ) { Text(stringResource(R.string.folder_continue)) }
    }
}

/**
 * A single selectable storage-volume row.
 *
 * Shows the volume label (e.g. "Internal shared storage") as the primary
 * text and the resolved Emulation/ path as secondary text. A radio button
 * on the left indicates the current selection.
 *
 * D-pad: the card is [selectable] so pressing A (DPAD_CENTER) on a focused
 * row picks it.  Selected row uses EmuTones.containerHigh + white border so
 * it reads clearly against the dark background.
 */
@Composable
private fun VolumeRow(
    volume: StorageVolumes.Volume,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumePath = if (volume.path.endsWith("/Emulation"))
        volume.path else "${volume.path.trimEnd('/')}/Emulation"

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Selected: white border + slightly elevated container to read clearly.
    // Focused:  thick white focus ring (D-pad ring).
    val borderStroke = when {
        focused   -> BorderStroke(2.dp, Color.White)
        selected  -> BorderStroke(1.dp, Color.White)
        else      -> BorderStroke(1.dp, EmuTones.outlineRest)
    }
    val containerColor = if (selected) EmuTones.containerHigh else EmuTones.surface

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(borderStroke, RoundedCornerShape(12.dp))
            .selectable(
                selected          = selected,
                enabled           = enabled,
                role              = Role.RadioButton,
                interactionSource = interaction,
                indication        = null,
                onClick           = onSelect,
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape  = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector     = if (volume.isRemovable) Icons.Outlined.SdCard else Icons.Outlined.Storage,
                contentDescription = null,
                tint            = if (selected) EmuTones.onSurface else EmuTones.onSurfaceVar,
                modifier        = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text       = volume.label,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = if (selected) EmuTones.onSurface else EmuTones.onSurface,
                )
                Text(
                    text  = volumePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmuTones.onSurfaceVar,
                )
            }
        }
    }
}
