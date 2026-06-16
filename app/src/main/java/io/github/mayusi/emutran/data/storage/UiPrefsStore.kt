package io.github.mayusi.emutran.data.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiPrefsDataStore by preferencesDataStore(name = "emutran_ui_prefs")

/**
 * One-off UI state that should survive process death but has nothing to do
 * with the setup pipeline. Kept separate from [SetupOptionsStore] so a future
 * "reset setup" wipe never re-shows a prompt the user already dismissed (and so
 * "UI prompt seen" doesn't get tangled with feature opt-ins).
 *
 * Currently only one flag — the first-launch Discord prompt — but designed so
 * adding "rated the app", "saw the X tip", etc. later is one key change.
 */
@Singleton
class UiPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val discordPromptShownKey = booleanPreferencesKey("discord_prompt_shown")

    /**
     * Whether the first-launch "Join our Discord" prompt has already been
     * surfaced. Default false — show it once, then flip true forever so it
     * never appears again (whether the user joined or dismissed).
     */
    val discordPromptShown: Flow<Boolean> = context.uiPrefsDataStore.data.map {
        it[discordPromptShownKey] ?: false
    }

    suspend fun setDiscordPromptShown(value: Boolean) {
        context.uiPrefsDataStore.edit { it[discordPromptShownKey] = value }
    }
}
