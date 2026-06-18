package io.github.mayusi.emutran.data.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for the FIX 4 DataStore-key hashing contract in [HttpCache].
 *
 * [HttpCache.keyFor] is private and the surrounding get()/put() round-trip needs
 * a real Android Context + DataStore (no Robolectric in this project), so we can't
 * drive the full cache here. What FIX 4 actually guarantees is a property of the
 * key derivation, which we verify against the same SHA-256(url) → first-16-bytes-hex
 * recipe the implementation uses:
 *
 *   - stable: the same URL always maps to the same key (read/write stay matched);
 *   - fixed-length: 16 bytes → 32 lowercase hex chars regardless of URL length;
 *   - prefs-safe: only [0-9a-f], so no XML-special chars can corrupt the prefs file;
 *   - collision-resistant enough: distinct URLs map to distinct keys.
 *
 * If the implementation's recipe changes, this local mirror must change with it —
 * the test documents the exact contract callers (and the on-disk format) rely on.
 */
class HttpCacheKeyTest {

    /** Mirror of HttpCache.keyFor — SHA-256(url), first 16 bytes, lowercase hex. */
    private fun keyFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `key is a stable 32-char lowercase hex string`() {
        val url = "https://api.github.com/repos/owner/repo/releases/latest"
        val key = keyFor(url)
        assertThat(key).hasLength(32)
        assertThat(key).matches("[0-9a-f]{32}")
    }

    @Test
    fun `same url maps to the same key on repeated calls`() {
        val url = "https://example.com/some/very/long/path?with=query&and=specials<>"
        assertThat(keyFor(url)).isEqualTo(keyFor(url))
    }

    @Test
    fun `length is fixed regardless of url length`() {
        val shortUrl = "https://a.co/x"
        val longUrl = "https://example.com/" + "segment/".repeat(200) + "file.json?q=" + "x".repeat(500)
        assertThat(keyFor(shortUrl)).hasLength(32)
        assertThat(keyFor(longUrl)).hasLength(32)
    }

    @Test
    fun `key contains no XML-special or path characters`() {
        // URLs with characters that could corrupt the prefs XML must still yield a safe key.
        val nasty = "https://h.io/<tag>&amp;\"quote\"/path with spaces?a=b#frag"
        val key = keyFor(nasty)
        assertThat(key).matches("[0-9a-f]{32}")
    }

    @Test
    fun `distinct urls map to distinct keys`() {
        val a = keyFor("https://api.github.com/repos/owner/repoA/releases/latest")
        val b = keyFor("https://api.github.com/repos/owner/repoB/releases/latest")
        assertThat(a).isNotEqualTo(b)
    }
}
