package io.github.mayusi.emutran.data.source

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.httpCacheStore by preferencesDataStore(name = "emutran_http_cache")

/**
 * ETag + cached-body store for HTTP responses we want to revalidate
 * cheaply. Used by GitHubReleasesSource so re-resolving the same repo
 * sends If-None-Match; on 304 we burn no rate-limit tokens and reuse
 * the body from cache.
 *
 * GitHub allows 60 unauthenticated requests per hour and (critically)
 * 304 responses DO NOT count against that limit. With 30+ emulators in
 * the picker, a returning user can easily blow through 60 requests.
 * This cache makes the steady-state cost ~zero.
 *
 * Persistence: in-memory map for the hot path, plus one DataStore
 * entry per URL so the cache survives process death. Memory cache is
 * populated lazily on first read.
 *
 * TTL: entries older than [DEFAULT_TTL_MS] (24 hours) are treated as
 * stale — [get] returns null so the caller re-fetches and gets a fresh
 * ETag. This prevents release metadata going infinitely stale if an
 * app stopped issuing ETags or GitHub flips behavior. Callers can pass
 * a custom TTL (e.g. manifest refresh uses a shorter window).
 */
@Singleton
class HttpCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val memCache = ConcurrentHashMap<String, Entry>()

    @Serializable
    data class Entry(val etag: String, val body: String, val savedAtMs: Long)

    /**
     * Returns the cached entry for [url] if it exists AND is not older
     * than [maxAgeMs]. Returns null if absent or stale, so the caller
     * re-fetches. Memory cache is checked first; disk is loaded lazily.
     */
    suspend fun get(url: String, maxAgeMs: Long = DEFAULT_TTL_MS): Entry? {
        val candidate = memCache[url] ?: run {
            val key = stringPreferencesKey(keyFor(url))
            val raw = context.httpCacheStore.data.first()[key] ?: return null
            val parsed = runCatching { json.decodeFromString<Entry>(raw) }.getOrNull()
                ?: return null
            memCache[url] = parsed
            parsed
        }
        // Reject entries that are older than the requested max-age.
        val ageMs = System.currentTimeMillis() - candidate.savedAtMs
        return if (ageMs <= maxAgeMs) candidate else null
    }

    /** Store [entry] for [url]. Overwrites any previous entry. */
    suspend fun put(url: String, entry: Entry) {
        memCache[url] = entry
        val key = stringPreferencesKey(keyFor(url))
        context.httpCacheStore.edit { it[key] = json.encodeToString(Entry.serializer(), entry) }
    }

    /**
     * FIX 4: Maps a raw [url] to a stable, fixed-length, prefs-safe DataStore key.
     *
     * Using the raw URL as the key risked corrupting the prefs file with long or
     * XML-special characters. Instead we hash the URL with SHA-256 and take the
     * first 32 hex chars (128 bits) — collision-safe for our scale and free of any
     * special characters. Applied to BOTH [get] and [put] so reads and writes stay
     * matched; the in-memory [memCache] still keys on the raw URL for the hot path.
     *
     * One-time effect: existing on-disk entries written under the old raw-URL keys
     * become unreachable (a single cache miss → one re-fetch → re-cache under the
     * new key). That is acceptable and self-healing.
     */
    private fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Default TTL: 24 hours. Stale entries are ignored, not deleted. */
        const val DEFAULT_TTL_MS: Long = 24 * 60 * 60 * 1_000L

        /**
         * Shorter TTL for the pack manifest: 6 hours. We'd rather re-fetch
         * once and re-cache than serve a day-old manifest to a user who
         * just opened the app after a pack update.
         */
        const val MANIFEST_TTL_MS: Long = 6 * 60 * 60 * 1_000L
    }
}
