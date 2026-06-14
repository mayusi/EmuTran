package io.github.mayusi.emutran.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────────────────────
// EmuTones — 5-step OLED elevation palette
//
// Instead of arbitrary grays, every surface sits at a defined tone level.
// Depth is communicated purely through brightness (Apple dark-mode idiom):
// the further a layer is from the OLED black substrate, the lighter it is.
// This makes the monochrome theme feel *intentional* rather than flat.
//
//  Level 0  bg               #000000  — OLED substrate, page background
//  Level 1  surface          #0D0D0D  — base card / list-item resting plane
//  Level 2  container        #141414  — surfaceContainer; inner sections
//  Level 3  containerHigh    #1C1C1C  — focused / active state highlight
//  Level 4  containerHighest #242424  — modal, bottom-sheet, dialog bg
//
// Card/screen agents: pick tones like this —
//   • Card at rest              → surface       (#0D0D0D)
//   • Card focused (D-pad ring) → containerHigh (#1C1C1C)
//   • Section container / chip  → container     (#141414)
//   • Dialog / bottom-sheet bg  → containerHighest (#242424)
//   • Divider line              → outlineDivider  (#383838)
//   • Rest-state card border    → outlineRest     (#222222)
//   • Secondary text / labels   → onSurfaceVar    (#A0A0A0)
// ─────────────────────────────────────────────────────────────────────────────
object EmuTones {
    /** OLED substrate — page background. Level 0. */
    val bg                 = Color(0xFF000000)

    /** Base card resting plane. Level 1. */
    val surface            = Color(0xFF0D0D0D)

    /** Inner sections, chip fills. Level 2 (= surfaceContainer). */
    val container          = Color(0xFF141414)

    /** Focused / active card highlight. Level 3 (= surfaceContainerHigh). */
    val containerHigh      = Color(0xFF1C1C1C)

    /** Modal, bottom-sheet, dialog background. Level 4 (= surfaceContainerHighest). */
    val containerHighest   = Color(0xFF242424)

    /** Visible divider lines inside cards or between sections. */
    val outlineDivider     = Color(0xFF383838)

    /** Rest-state card border / subtle stroke. */
    val outlineRest        = Color(0xFF222222)

    /** Primary (white) body text / icons on dark surfaces. */
    val onSurface          = Color(0xFFFFFFFF)

    /** Secondary text — labels, captions, placeholder copy. */
    val onSurfaceVar       = Color(0xFFA0A0A0)
}

// ─────────────────────────────────────────────────────────────────────────────
// Color scheme
//
// All Material3 darkColorScheme params are filled explicitly so the IDE
// never falls back to its magenta/teal defaults. The five elevation tones
// from EmuTones are mirrored here via the native M3 surface-tier params
// (available in compose-bom ≥ 2024.09 / material3 ≥ 1.3.0).
//
// Badge grays — load-bearing, do not change:
//   secondary  #888888  → 'Recommended' badge (medium gray)
//   tertiary   #D0D0D0  → 'Installed'    badge (near-white gray)
// They stay distinguishable purely from brightness; their text labels
// independently carry the meaning, satisfying WCAG non-text contrast.
//
// error uses off-white (not red) — the word "Failed" conveys the state,
// keeping the UI entirely hue-free.
// ─────────────────────────────────────────────────────────────────────────────
private val EmuTranColors = darkColorScheme(
    // Primary action — white fill, black text
    primary              = Color(0xFFFFFFFF),
    onPrimary            = Color(0xFF000000),
    primaryContainer     = Color(0xFFE0E0E0), // selected/checked card bg (light gray)
    onPrimaryContainer   = Color(0xFF000000),

    // Secondary — 'Recommended' badge (medium gray)
    secondary            = Color(0xFF888888),
    onSecondary          = Color(0xFF000000),
    secondaryContainer   = Color(0xFF1C1C1C), // mirrors containerHigh
    onSecondaryContainer = Color(0xFFFFFFFF),

    // Tertiary — 'Installed' badge (near-white gray)
    tertiary             = Color(0xFFD0D0D0),
    onTertiary           = Color(0xFF000000),
    tertiaryContainer    = Color(0xFF242424), // mirrors containerHighest
    onTertiaryContainer  = Color(0xFFFFFFFF),

    // Page substrate — pure OLED black (Level 0)
    background           = EmuTones.bg,
    onBackground         = EmuTones.onSurface,

    // Base card plane (Level 1)
    surface              = EmuTones.surface,
    onSurface            = EmuTones.onSurface,

    // Inner section / chip fill (Level 2) — via surfaceVariant / surfaceContainer
    surfaceVariant       = EmuTones.container,
    onSurfaceVariant     = EmuTones.onSurfaceVar,

    // Native M3 surface-tier params (material3 ≥ 1.3.0 / BOM ≥ 2024.09)
    surfaceContainer         = EmuTones.container,        // Level 2
    surfaceContainerHigh     = EmuTones.containerHigh,    // Level 3 — focused/active
    surfaceContainerHighest  = EmuTones.containerHighest, // Level 4 — modal/dialog
    surfaceContainerLow      = EmuTones.surface,          // same as base, not used separately
    surfaceContainerLowest   = EmuTones.bg,               // OLED floor

    // Strokes / outlines
    outline              = EmuTones.outlineDivider, // visible dividers
    outlineVariant       = EmuTones.outlineRest,    // rest-state card border

    // Error — off-white, no hue. Label text carries meaning.
    error                = Color(0xFFE0E0E0),
    onError              = Color(0xFF000000),
    errorContainer       = Color(0xFF1C1C1C),
    onErrorContainer     = Color(0xFFE0E0E0),

    // Inverse / scrim (keep them in-family)
    inverseSurface       = Color(0xFFE8E8E8),
    inverseOnSurface     = Color(0xFF000000),
    inversePrimary       = Color(0xFF000000),
    scrim                = Color(0xFF000000),
    surfaceTint          = Color(0xFFFFFFFF), // M3 elevation overlay; kept neutral white
)

// ─────────────────────────────────────────────────────────────────────────────
// Typography
//
// Clear hierarchy via weight and size alone — no color needed. The scale
// follows Material3 naming; screen agents should pick the right slot:
//
//   headlineMedium  26sp SemiBold  → page/section hero titles
//   titleLarge      22sp SemiBold  → screen titles in top bars
//   titleMedium     16sp SemiBold  → card headers, list section labels
//   bodyLarge       16sp Normal    → primary body copy
//   bodyMedium      14sp Normal    → secondary body / card descriptions
//   labelMedium     12sp Medium    → chip labels, badge text, captions
//   labelSmall      11sp Medium    → timestamps, fine print
//
// System font family only — no custom font files required or bundled.
// ─────────────────────────────────────────────────────────────────────────────
private val EmuTranTypography = Typography(
    // Hero / section titles
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),

    // Screen / card titles
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),

    // Body copy
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),

    // Labels — chips, badges, captions, timestamps
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize   = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ─────────────────────────────────────────────────────────────────────────────
// Public theme composable — signature unchanged; MainActivity keeps compiling.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Force-dark Material 3 theme tuned for handheld OLED panels.
 *
 * Why not follow the system theme: handhelds are used in every lighting
 * condition (couch, outside, plane), and an emulation tool with a giant
 * white background scorches the eyes in the dark. We pick the look that
 * works everywhere instead of letting the OS toggle it. The theme is
 * pure monochrome (black/white/gray) — no hue at all.
 *
 * Why not dynamicColor: dynamic color (Material You) pulls from the user's
 * wallpaper, which gives wildly different button contrast per device. We
 * want EmuTran to look identical on every Odin, Retroid, and Anbernic.
 *
 * Elevation model: depth is expressed via brightness, not shadows. The five
 * tones in [EmuTones] step from pure OLED black (Level 0) up to #242424
 * (Level 4 — modal/dialog). Card agents: use [EmuTones] directly for the
 * exact focused-state and rest-state container colors; the Material3
 * colorScheme params mirror the same values for components that read them
 * automatically (ModalBottomSheet, AlertDialog, etc.).
 *
 * Status-bar icons: forced light (white) so they read against our dark bg.
 */
@Composable
fun EmuTranTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // We're drawing edge-to-edge; insets are respected at the
            // screen level via Modifier.systemBarsPadding(). Force the
            // status-bar icons to light so they read against our dark bg.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = EmuTranColors,
        typography  = EmuTranTypography,
        content     = content,
    )
}
