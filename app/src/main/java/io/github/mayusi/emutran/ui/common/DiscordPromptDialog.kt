package io.github.mayusi.emutran.ui.common

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.ui.theme.EmuTones

/**
 * First-launch-only "Join our Discord" prompt.
 *
 * Shown once ever (gated by a persisted flag in DiscordPromptViewModel); after
 * the user joins or dismisses, the flag flips true and the prompt never returns.
 *
 * Mirrors [AppInfoDialog]'s monochrome Material3 styling and D-pad behavior:
 * dialog bg = [EmuTones.containerHighest], focus trapped inside, the primary
 * "Join Discord" action receives initial focus so an A-press fires immediately,
 * and B / system back dismisses.
 *
 * The invite is opened via [Intent.ACTION_VIEW] — the same mechanism the About
 * screen uses for the GitHub link — and the URL comes from the single
 * [DISCORD_INVITE_URL] constant.
 */
@Composable
fun DiscordPromptDialog(
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current

    // D-pad initial focus: the primary "Join Discord" action.
    val joinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { joinFocus.requestFocus() } catch (_: Exception) {}
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmuTones.containerHighest,
        titleContentColor = EmuTones.onSurface,
        textContentColor = EmuTones.onSurface,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Join the EmuTran community",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = "Get help with setup, share your handheld configs, and hear " +
                    "about new emulators first. Hop into our Discord — it's the " +
                    "fastest way to get unstuck.",
                style = MaterialTheme.typography.bodyMedium,
                color = EmuTones.onSurface,
            )
        },
        confirmButton = {
            val joinInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_INVITE_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onJoin()
                },
                interactionSource = joinInteraction,
                modifier = Modifier
                    .focusRequester(joinFocus)
                    .dpadFocusBorder(joinInteraction, cornerRadius = 8.dp),
            ) {
                Text("Join Discord")
            }
        },
        dismissButton = {
            val laterInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = onDismiss,
                interactionSource = laterInteraction,
                modifier = Modifier.dpadFocusBorder(laterInteraction, cornerRadius = 8.dp),
            ) {
                Text("Maybe later")
            }
        },
    )
}
