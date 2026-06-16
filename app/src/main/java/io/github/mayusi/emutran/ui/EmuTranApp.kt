package io.github.mayusi.emutran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.mayusi.emutran.ui.about.AboutScreen
import io.github.mayusi.emutran.ui.common.DiscordPromptDialog
import io.github.mayusi.emutran.ui.dashboard.DashboardScreen
import io.github.mayusi.emutran.ui.health.HealthCheckScreen
import io.github.mayusi.emutran.ui.deviceinfo.DeviceInfoScreen
import io.github.mayusi.emutran.ui.done.DoneScreen
import io.github.mayusi.emutran.ui.permissions.PermissionsScreen
import io.github.mayusi.emutran.ui.pickapps.PickAppsScreen
import io.github.mayusi.emutran.ui.pickapps.QuickSetupViewModel
import io.github.mayusi.emutran.ui.pickfolder.PickFolderScreen
import io.github.mayusi.emutran.ui.profile.ProfileScreen
import io.github.mayusi.emutran.ui.progress.ProgressScreen
import io.github.mayusi.emutran.ui.shizuku.ShizukuScreen
import io.github.mayusi.emutran.ui.splash.SplashScreen

/**
 * Top-level navigation graph.
 *
 * On launch, [AppBootstrapViewModel] decides whether to start the user
 * in the setup flow (SPLASH) or the post-setup dashboard (DASHBOARD).
 * Until it decides, we render an empty splash background so the user
 * never sees a flash of a wrong screen.
 *
 * Setup flow is linear; dashboard is a hub that can re-enter the setup
 * flow if the user wants to add more emulators.
 *
 * Quick Setup flow (from Dashboard):
 *   [QuickSetupViewModel] commits the recommended selection to
 *   SelectedAppsStore, then we navigate directly to PROGRESS, skipping
 *   the manual picker. The normal PICK_FOLDER → PICK_APPS path is still
 *   available for users who want full control.
 */
object Routes {
    const val SPLASH = "splash"
    const val PERMISSIONS = "permissions"
    const val DEVICE_INFO = "device_info"
    const val PICK_FOLDER = "pick_folder"
    const val PICK_APPS = "pick_apps"
    const val SHIZUKU = "shizuku"
    const val PROGRESS = "progress"
    const val DONE = "done"
    const val DASHBOARD = "dashboard"
    const val ABOUT = "about"
    const val HEALTH = "health"
    const val PROFILE = "profile"
}

// The top-level Scaffold intentionally ignores its content padding: each NavHost
// destination owns its own insets (via systemBarsPadding / its own Scaffold), so
// the outer padding is deliberately unused here.
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun EmuTranApp(
    bootstrap: AppBootstrapViewModel = hiltViewModel(),
    discordPrompt: DiscordPromptViewModel = hiltViewModel(),
) {
    val startDestination by bootstrap.startDestination.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (startDestination == null) {
            // Still figuring out where to go. Render the same dark
            // background as Theme.kt so there's no flash; SplashScreen
            // would also work but it auto-advances after 1.5s which
            // could race the bootstrap decision.
            Box(modifier = Modifier.fillMaxSize())
            return@Box
        }

        val navController = rememberNavController()

        // FIX 4: track whether the last completed setup run was fully successful.
        // ProgressScreen's onDone callback sets this before navigating to DONE,
        // so DoneScreen can show celebratory vs neutral UI accordingly.
        var lastSetupSuccess by rememberSaveable { mutableStateOf(true) }

        // FIX 7: snackbar host for QuickSetup failure messages.
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            snackbarHost = {
                SnackbarHost(snackbarHostState)
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { _ ->
            NavHost(
                navController = navController,
                startDestination = startDestination!!,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                composable(Routes.SPLASH) {
                    SplashScreen(onContinue = { navController.navigate(Routes.PERMISSIONS) })
                }
                composable(Routes.PERMISSIONS) {
                    PermissionsScreen(onContinue = { navController.navigate(Routes.DEVICE_INFO) })
                }
                composable(Routes.DEVICE_INFO) {
                    DeviceInfoScreen(onContinue = { navController.navigate(Routes.PICK_FOLDER) })
                }
                composable(Routes.PICK_FOLDER) {
                    PickFolderScreen(onContinue = { navController.navigate(Routes.PICK_APPS) })
                }
                composable(Routes.PICK_APPS) {
                    PickAppsScreen(onContinue = { navController.navigate(Routes.SHIZUKU) })
                }
                composable(Routes.SHIZUKU) {
                    ShizukuScreen(onContinue = { navController.navigate(Routes.PROGRESS) })
                }
                composable(Routes.PROGRESS) {
                    // Helper lambda to navigate away to dashboard from Failed/cancel states
                    val goToDashboard = {
                        navController.navigate(Routes.DASHBOARD) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                        }
                    }
                    ProgressScreen(
                        onDone = { success ->
                            lastSetupSuccess = success
                            navController.navigate(Routes.DONE)
                        },
                        onGoToDashboard = goToDashboard,
                    )
                }
                composable(Routes.DONE) {
                    DoneScreen(
                        success = lastSetupSuccess,
                        onGoToDashboard = {
                            // Pop the entire setup flow off the back stack so
                            // the user cannot Back back into it.
                            navController.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.SPLASH) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.DASHBOARD) {
                    // QuickSetupViewModel scoped to the DASHBOARD back-stack entry
                    // so it is created once and survives recomposition.
                    val quickVm: QuickSetupViewModel = hiltViewModel()
                    val quickState by quickVm.state.collectAsStateWithLifecycle()

                    // When the ViewModel signals it has committed the recommended
                    // selection, navigate to PROGRESS.
                    // FIX 7: also handle State.Failed — show a snackbar and reset.
                    LaunchedEffect(quickState) {
                        when (val qs = quickState) {
                            is QuickSetupViewModel.State.Ready -> {
                                quickVm.reset()
                                navController.navigate(Routes.PROGRESS)
                            }
                            is QuickSetupViewModel.State.Failed -> {
                                // Keep Failed visible long enough for the snackbar, then reset.
                                snackbarHostState.showSnackbar(
                                    message = "Quick Setup failed: ${qs.message}",
                                    duration = SnackbarDuration.Long,
                                )
                                quickVm.reset()
                            }
                            else -> Unit
                        }
                    }

                    DashboardScreen(
                        onAddMore = {
                            navController.navigate(Routes.PICK_APPS)
                        },
                        onRunFullSetup = {
                            navController.navigate(Routes.PERMISSIONS)
                        },
                        onAbout = {
                            navController.navigate(Routes.ABOUT)
                        },
                        onQuickSetup = {
                            // Commit recommended set then navigate to PROGRESS
                            // (handled above via LaunchedEffect on quickState).
                            quickVm.commitRecommended()
                        },
                        onHealthCheck = {
                            navController.navigate(Routes.HEALTH)
                        },
                        onProfile = {
                            navController.navigate(Routes.PROFILE)
                        },
                    )
                }
                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.HEALTH) {
                    HealthCheckScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onBack = { navController.popBackStack() },
                        onImported = { toProgress ->
                            if (toProgress) {
                                // Run the just-restored setup. Drop PROFILE off the
                                // back stack so Back from PROGRESS won't re-enter it.
                                navController.navigate(Routes.PROGRESS) {
                                    popUpTo(Routes.PROFILE) { inclusive = true }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        },
                    )
                }
            }
        }

        // First-launch-only "Join our Discord" prompt. Rendered as an overlay
        // above the Scaffold/NavHost. Because this code is only reached after
        // the early `startDestination == null` return above, the dialog can
        // never flash over the blank bootstrap/splash background — it appears
        // only once a real destination has been resolved, and only the first
        // time ever (gated by the persisted flag in DiscordPromptViewModel).
        val showDiscordPrompt by discordPrompt.showPrompt.collectAsStateWithLifecycle()
        if (showDiscordPrompt) {
            DiscordPromptDialog(
                onJoin = discordPrompt::onJoin,
                onDismiss = discordPrompt::dismiss,
            )
        }
    }
}
