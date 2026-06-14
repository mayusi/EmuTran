package io.github.mayusi.emutran.ui.testinstall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.rememberOnResume

/**
 * Throwaway screen for verifying the download + install pipeline on real
 * hardware. Will be deleted once the real emulator picker exists.
 */
@Composable
fun TestInstallScreen(
    vm: TestInstallViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Re-runs on ON_RESUME so flipping "Install unknown apps" in Settings
    // and coming back makes the warning disappear automatically.
    val canInstall by rememberOnResume { vm.canInstall() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.ItemGap),
    ) {
        Text(
            text = "Pipeline test",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Downloads Flycast (Dreamcast emulator, ~31 MB) from its " +
                "GitHub release and hands it to Android's installer. Proves " +
                "the download → install path works end-to-end before we " +
                "wire up the full picker.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!canInstall) {
            Text(
                text = "Android needs your permission to let EmuTran install " +
                    "other apps. Tap below, enable \"Allow from this source\", " +
                    "then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { vm.openInstallSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.ButtonMinHeight),
            ) { Text("Open install settings") }
        }

        Button(
            onClick = { if (canInstall) vm.runTest() },
            enabled = state !is TestInstallViewModel.State.Downloading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.ButtonMinHeight),
        ) { Text("Download + install Flycast") }

        when (val s = state) {
            TestInstallViewModel.State.Idle -> Unit
            is TestInstallViewModel.State.Downloading -> {
                val pct = if (s.total > 0) s.downloaded.toFloat() / s.total else 0f
                LinearProgressIndicator(
                    progress = { pct },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${humanBytes(s.downloaded)} / ${humanBytes(s.total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is TestInstallViewModel.State.LaunchedInstaller -> {
                Text(
                    text = "Download complete (${humanBytes(s.sizeBytes)}). " +
                        "Android's installer should be up now — tap Install.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            is TestInstallViewModel.State.Failed -> {
                Text(
                    text = "Failed: ${s.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes <= 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
