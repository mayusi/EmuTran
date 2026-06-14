package io.github.mayusi.emutran.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Shared spacing/sizing tokens. One place to bump everything if buttons
 * still feel small for D-pad / thumb use after device testing.
 */
object Dimens {
    /** Outer page padding — generous so content doesn't crowd screen edges. */
    val ScreenPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)

    /** Minimum height for any primary action button. Material default is 48dp;
     *  handhelds benefit from a bigger target especially under controller focus. */
    val ButtonMinHeight = 56.dp

    /** Vertical space between top-level page sections. */
    val SectionGap = 20.dp

    /** Tighter gap, for related items inside a section. */
    val ItemGap = 12.dp
}
