package io.github.mayusi.emutran.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mayusi.emutran.BuildConfig
import io.github.mayusi.emutran.data.auth.GithubTokenStore
import io.github.mayusi.emutran.ui.common.Dimens
import io.github.mayusi.emutran.ui.common.SelfUpdateSheet
import io.github.mayusi.emutran.ui.common.dpadFocusBorder
import io.github.mayusi.emutran.ui.common.dpadFocusable
import io.github.mayusi.emutran.ui.theme.EmuTones
import kotlinx.coroutines.launch

private const val GITHUB_URL = "https://github.com/mayusi/EmuTran"

/**
 * About / version screen.
 *
 * Self-update sheet is now rendered via the shared [SelfUpdateSheet] composable
 * from ui/common (FIX 4), eliminating the previous duplication between this
 * screen and the dashboard.
 *
 * The GitHub PAT section ([GithubTokenSection]) is left intact — only the
 * self-update sheet composable was replaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    vm: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val githubToken by vm.githubToken.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // D-pad: land on the Back button so B (system back) and A both work.
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { backFocus.requestFocus() } catch (_: Exception) {}
    }

    // Show snackbars for terminal states.
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is AboutViewModel.SelfUpdateUiState.UpToDate ->
                snackbarHostState.showSnackbar("You're on the latest version")
            is AboutViewModel.SelfUpdateUiState.Failed ->
                snackbarHostState.showSnackbar("Update check failed: ${s.reason}")
            is AboutViewModel.SelfUpdateUiState.Launching ->
                snackbarHostState.showSnackbar("Opening system installer…")
            // DEFECT 1: the OS blocked the install because "Install unknown apps"
            // is off for EmuTran. Offer an actionable snackbar that deep-links to
            // the settings page; once enabled the user taps the snackbar again to
            // retry the cached download.
            is AboutViewModel.SelfUpdateUiState.NeedsInstallPermission -> {
                val result = snackbarHostState.showSnackbar(
                    message = "Allow EmuTran to install apps, then retry.",
                    actionLabel = "Open settings",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    vm.openInstallPermissionSettings()
                    // Reset so the next "Update now" tap re-runs the gated install.
                    vm.dismissSheet()
                } else {
                    vm.dismissSheet()
                }
            }
            else -> Unit
        }
        if (uiState is AboutViewModel.SelfUpdateUiState.UpToDate ||
            uiState is AboutViewModel.SelfUpdateUiState.Failed ||
            uiState is AboutViewModel.SelfUpdateUiState.Launching
        ) {
            vm.dismissSheet()
        }
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Back row ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backFocus),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── App identity ──────────────────────────────────────────────
            Text(
                text = "EmuTran",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "One-tap emulation setup for Android handhelds.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Open source (MIT).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Action buttons ─────────────────────────────────────────────
            val githubInteraction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
                interactionSource = githubInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusBorder(githubInteraction, cornerRadius = 50.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "View on GitHub",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // "Check for updates" button — triggers self-update check.
            val checkInteraction = remember { MutableInteractionSource() }
            val isChecking = uiState is AboutViewModel.SelfUpdateUiState.Checking
            OutlinedButton(
                onClick = { if (!isChecking) vm.checkForSelfUpdate() },
                enabled = !isChecking,
                interactionSource = checkInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusBorder(checkInteraction, cornerRadius = 50.dp),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = EmuTones.onSurfaceVar,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…", style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Check for updates", style = MaterialTheme.typography.labelLarge)
                }
            }

            HorizontalDivider(color = EmuTones.outlineDivider)

            // ── GitHub rate-limit token ────────────────────────────────────
            GithubTokenSection(
                currentToken = githubToken,
                snackbarHostState = snackbarHostState,
                onSave = { vm.setGithubToken(it) },
                onClear = { vm.clearGithubToken() },
            )

            HorizontalDivider(color = EmuTones.outlineDivider)

            // ── Credits ───────────────────────────────────────────────────
            Text(
                text = "Credits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Emulator list from the Obtainium Emulation Pack (RJNY). " +
                    "Silent install via Shizuku (RikkaApps).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Created by mayusi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // ── "What's new" bottom sheet ──────────────────────────────────────────
    // FIX 4: replaced the local SelfUpdateSheetContent composable with the shared
    // SelfUpdateSheet from ui/common. The Available/Downloading states are resolved
    // here and passed as primitive params.
    val available = uiState as? AboutViewModel.SelfUpdateUiState.Available
    val downloading = uiState as? AboutViewModel.SelfUpdateUiState.Downloading
    val showSheet = available != null || downloading != null

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        // Resolve display params — version is carried by both Available and Downloading.
        val sheetVersion = available?.version ?: downloading?.version ?: ""
        val sheetChangelog = available?.changelog ?: ""
        val sheetApkUrl = available?.apkUrl
        val downloadingPercent = downloading?.percent

        ModalBottomSheet(
            onDismissRequest = vm::dismissSheet,
            sheetState = sheetState,
            containerColor = EmuTones.containerHighest,
            contentColor = EmuTones.onSurface,
        ) {
            // About screen does NOT have a "Skip this release" flow (no banner to skip).
            // Pass onSkip = null so the button is hidden.
            SelfUpdateSheet(
                version = sheetVersion,
                changelogMarkdown = sheetChangelog,
                downloadingPercent = downloadingPercent,
                onUpdateNow = { sheetApkUrl?.let { vm.downloadAndInstall(it) } },
                onSkip = null,
                onDismiss = vm::dismissSheet,
            )
        }
    }
}

// ── GitHub token section ─────────────────────────────────────────────────────

/**
 * Returns a masked display string for a GitHub PAT.
 */
private fun maskToken(token: String): String {
    if (token.length <= 8) return "••••••••"
    val prefix = token.take(4)
    val suffix = token.takeLast(4)
    return "$prefix••••••••$suffix"
}

/**
 * Renders the optional GitHub PAT configuration UI.
 *
 * When no token is stored: shows an explainer + a masked [OutlinedTextField]
 * (with show/hide toggle) for the user to paste a token + a "Save" button.
 *
 * When a token is already stored: shows the masked token + a "Clear" button.
 *
 * Monochrome, D-pad focusable.
 */
@Composable
private fun GithubTokenSection(
    currentToken: String?,
    snackbarHostState: SnackbarHostState,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    val hasToken = !currentToken.isNullOrBlank()
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "GitHub API token (optional)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Add a read-only GitHub token to raise the API rate limit from " +
                "60 to 5,000 requests/hour during Quick Setup. No scopes required — " +
                "generate one at github.com/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (hasToken) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = maskToken(currentToken!!),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val clearInteraction = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        draft = ""
                        onClear()
                    },
                    interactionSource = clearInteraction,
                    modifier = Modifier.dpadFocusBorder(clearInteraction, cornerRadius = 50.dp),
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        text = "ghp_…",
                        color = EmuTones.onSurfaceVar,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(
                            imageVector = if (showToken) Icons.Outlined.VisibilityOff
                                          else Icons.Outlined.Visibility,
                            contentDescription = if (showToken) "Hide token" else "Show token",
                            tint = EmuTones.onSurfaceVar,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = EmuTones.onSurface,
                    unfocusedTextColor = EmuTones.onSurface,
                    focusedBorderColor = EmuTones.onSurface,
                    unfocusedBorderColor = EmuTones.outlineDivider,
                    cursorColor = EmuTones.onSurface,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusable(cornerRadius = 4.dp),
            )

            if (draft.isNotBlank() && !GithubTokenStore.looksLikeValidPat(draft)) {
                Text(
                    text = "Token format looks unusual — expected ghp_, github_pat_, or similar. " +
                        "It will still be saved.",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmuTones.onSurfaceVar,
                )
            }

            val saveInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        onSave(draft)
                        draft = ""
                        showToken = false
                        scope.launch { snackbarHostState.showSnackbar("Token saved") }
                    }
                },
                enabled = draft.isNotBlank(),
                interactionSource = saveInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusBorder(saveInteraction, cornerRadius = 50.dp),
            ) {
                Text("Save token", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
