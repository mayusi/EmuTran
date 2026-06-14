package io.github.mayusi.emutran.ui.deviceinfo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.R
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.StepIndicator
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.theme.EmuTones

@Composable
fun DeviceInfoScreen(
    onContinue: () -> Unit,
    vm: DeviceInfoViewModel = hiltViewModel(),
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val override by vm.userOverrideDualScreen.collectAsStateWithLifecycle()

    val selectedIndex = when (override ?: profile.isDualScreenGuess) {
        true -> 1
        false -> 0
    }

    // D-pad: land on Continue immediately (segmented buttons respond to D-pad natively).
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { continueFocus.requestFocus() } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        // Step indicator — step 2 of 6.
        StepIndicator(current = 2)

        Text(
            text  = stringResource(R.string.device_title),
            style = MaterialTheme.typography.headlineMedium,
        )

        // Spec card — label (secondary) left, value (white) right.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = EmuTones.surface),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SpecRow("Manufacturer", profile.manufacturer)
                SpecDivider()
                SpecRow("Model",        profile.model)
                SpecDivider()
                SpecRow("Vendor",       profile.vendor.display)
                SpecDivider()
                SpecRow("Android",      "${profile.androidVersion} (API ${profile.sdkInt})")
                SpecDivider()
                SpecRow("Hardware",     profile.hardware.ifEmpty { "—" })
                SpecDivider()
                // Convert raw MB to human-readable GB (round to nearest GB).
                SpecRow("RAM",          if (profile.totalRamMb > 0) formatRam(profile.totalRamMb) else "—")
            }
        }

        Text(
            text  = stringResource(R.string.device_screen_type),
            style = MaterialTheme.typography.titleMedium,
        )

        // Segmented button with explicit monochrome colours:
        //   selected   → white container + black text
        //   unselected → transparent + EmuTones.outlineRest border + white text
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val labels = listOf(
                stringResource(R.string.device_standard),
                stringResource(R.string.device_dual),
            )
            labels.forEachIndexed { index, label ->
                val isSelected = index == selectedIndex
                SegmentedButton(
                    selected = isSelected,
                    onClick  = { vm.setDualScreen(index == 1) },
                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    colors   = SegmentedButtonDefaults.colors(
                        activeContainerColor   = Color.White,
                        activeContentColor     = Color.Black,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor   = EmuTones.onSurface,
                        activeBorderColor      = Color.White,
                        inactiveBorderColor    = EmuTones.outlineRest,
                    ),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }

        val continueInteraction = remember { MutableInteractionSource() }
        Button(
            onClick           = onContinue,
            interactionSource = continueInteraction,
            modifier          = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight)
                .focusRequester(continueFocus)
                .dpadFocusBorder(continueInteraction, cornerRadius = 50.dp),
        ) { Text(stringResource(R.string.device_continue)) }
    }
}

/** Spec table row: label in secondary gray, value in white. */
@Composable
private fun SpecRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelMedium,
            color = EmuTones.onSurfaceVar,
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodyMedium,
            color = EmuTones.onSurface,
        )
    }
}

@Composable
private fun SpecDivider() {
    HorizontalDivider(color = EmuTones.outlineDivider, thickness = 0.5.dp)
}

/**
 * Convert raw megabytes from ActivityManager to a human-readable string.
 * Values in the 1000–16 000 MB range (handheld RAM) round to the nearest GB.
 * Values below 1 GB fall back to "N MB".
 */
private fun formatRam(mb: Long): String {
    if (mb < 1024L) return "$mb MB"
    val gb = (mb / 1024.0 + 0.5).toInt()   // round half-up
    return "~$gb GB"
}
