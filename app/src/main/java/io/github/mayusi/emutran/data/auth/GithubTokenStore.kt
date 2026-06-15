package io.github.mayusi.emutran.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore by preferencesDataStore(name = "emutran_auth")

/**
 * Persists an optional GitHub Personal Access Token (PAT) using a private
 * DataStore. The token lifts the GitHub API rate limit from 60 req/hr
 * (unauthenticated) to 5,000 req/hr (authenticated).
 *
 * The token requires NO scopes — public read access is sufficient.
 *
 * Security notes:
 *   - Stored in app-private DataStore only (never logged, never backed up;
 *     AndroidManifest has allowBackup=false).
 *   - Only ever forwarded to api.github.com (enforced by the OkHttp
 *     interceptor in AppModule — not here).
 *   - [_tokenState] is a SharingStarted.Eagerly StateFlow: it starts
 *     collecting immediately on construction so the very first GitHub API
 *     call on app launch is authenticated even before any subscriber exists.
 *     [currentToken] reads from this StateFlow — always non-blocking and
 *     never races the first DataStore emission.
 */
@Singleton
class GithubTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    // Application-scoped CoroutineScope (SupervisorJob + Dispatchers.IO) provided
    // by AppModule. Injected rather than constructed inline so tests can supply a
    // controlled scope; a child failure never cancels it (SupervisorJob).
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val key = stringPreferencesKey("github_token")

    /** Raw token flow — emits null/blank when unset. */
    private val rawToken: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[key]?.takeIf { it.isNotBlank() }
    }

    /**
     * Eagerly-started StateFlow of the stored token. Starts collecting from
     * DataStore immediately on construction (SharingStarted.Eagerly), so
     * [currentToken] reflects the persisted value as soon as the first IO
     * read completes — typically within milliseconds of app start. The OkHttp
     * interceptor reads [currentToken] synchronously on every request; this
     * approach avoids runBlocking while eliminating the cold-start null window
     * that caused the first API call to go unauthenticated.
     */
    private val _tokenState: StateFlow<String?> = rawToken.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    /** Flow of the raw stored token; emits null/blank when unset. Collect for reactive UI. */
    val token: Flow<String?> = rawToken

    /**
     * Returns the current token synchronously (null before the first DataStore
     * read completes, reliably set afterwards). Safe to call from OkHttp
     * interceptors — never blocks.
     */
    fun currentToken(): String? = _tokenState.value

    /**
     * Persists [token]. Trims whitespace; clears the stored value if blank.
     * Does NOT log the token value.
     */
    suspend fun setToken(token: String?) {
        val trimmed = token?.trim()
        context.authDataStore.edit { prefs ->
            if (trimmed.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = trimmed
            }
        }
    }

    companion object {
        /**
         * Known GitHub PAT prefix formats. A token not starting with one of
         * these is likely mistyped or is an older format (which still works,
         * but the hint helps catch obvious copy/paste errors).
         *
         * Prefixes per GitHub docs:
         *   ghp_  — classic PAT
         *   github_pat_ — fine-grained PAT
         *   gho_  — OAuth token
         *   ghu_  — user-to-server token
         *   ghs_  — server-to-server token
         *   ghr_  — refresh token
         */
        private val KNOWN_PAT_PREFIXES = listOf(
            "ghp_", "github_pat_", "gho_", "ghu_", "ghs_", "ghr_",
        )

        /**
         * Returns true when [token] starts with a known GitHub PAT prefix.
         * Returns false for blank/null input so callers can guard on non-empty
         * first if needed.
         *
         * This is a format hint only — tokens that don't match are still saved
         * (older classic PATs may lack the prefix).
         */
        fun looksLikeValidPat(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            return KNOWN_PAT_PREFIXES.any { token.startsWith(it) }
        }
    }
}
