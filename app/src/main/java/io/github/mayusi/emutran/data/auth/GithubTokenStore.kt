package io.github.mayusi.emutran.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
 *   - [cachedToken] is updated by collecting the DataStore flow on init so
 *     the synchronous OkHttp interceptor can read it without runBlocking.
 */
@Singleton
class GithubTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("github_token")

    // Background scope for collecting the DataStore flow into cachedToken.
    // SupervisorJob so that a child failure never cancels the scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Cached copy of the token, updated by collecting the DataStore flow on
     * init. The OkHttp interceptor reads this field synchronously — never
     * call runBlocking in an interceptor.
     */
    @Volatile
    private var cachedToken: String? = null

    /** Flow of the raw stored token; emits null/blank when unset. */
    val token: Flow<String?> = context.authDataStore.data.map { prefs ->
        prefs[key]?.takeIf { it.isNotBlank() }
    }

    init {
        // Populate cachedToken immediately and keep it current.
        scope.launch {
            token.collect { value ->
                cachedToken = value
            }
        }
    }

    /**
     * Returns the current token synchronously (may return null before the
     * first DataStore read completes, but is reliable in steady state).
     * Intended for use in OkHttp interceptors where coroutines cannot be used.
     */
    fun currentToken(): String? = cachedToken

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
}
