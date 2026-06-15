package io.github.mayusi.emutran.data.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [parseSha256SidecarBody].
 *
 * This is a top-level package function in GithubDtos.kt — no mocks needed.
 *
 * Two sidecar formats are handled:
 *   - Bare hex:         "abc123…def"       (64 lowercase hex chars)
 *   - sha256sum format: "abc123…def  filename.apk"
 *
 * The function always returns lowercase, or null on bad/missing data.
 */
class Sha256SidecarParserTest {

    /** A valid 64-char all-lowercase hex SHA-256 hash. */
    private val VALID_HASH = "a".repeat(64)

    /** An uppercase version of the same hash — must be lowercased on output. */
    private val UPPER_HASH = "A".repeat(64)

    /** A mixed-case version with all valid hex digits. */
    private val MIXED_HASH = "0123456789abcdefABCDEF".padEnd(64, '0').take(64)

    // ── Bare-hex format ───────────────────────────────────────────────────────

    @Test
    fun `bare lowercase 64-hex hash is returned as-is`() {
        val result = parseSha256SidecarBody(VALID_HASH)
        assertThat(result).isEqualTo(VALID_HASH)
    }

    @Test
    fun `bare uppercase hash is lowercased`() {
        val result = parseSha256SidecarBody(UPPER_HASH)
        assertThat(result).isEqualTo(UPPER_HASH.lowercase())
    }

    @Test
    fun `mixed case hash is lowercased`() {
        val result = parseSha256SidecarBody(MIXED_HASH)
        assertThat(result).isEqualTo(MIXED_HASH.lowercase())
    }

    // ── sha256sum format ──────────────────────────────────────────────────────

    @Test
    fun `sha256sum format - two-space separator - extracts first token`() {
        val input = "$VALID_HASH  filename.apk"
        val result = parseSha256SidecarBody(input)
        assertThat(result).isEqualTo(VALID_HASH)
    }

    @Test
    fun `sha256sum format - single space separator - extracts first token`() {
        val input = "$VALID_HASH filename.apk"
        val result = parseSha256SidecarBody(input)
        assertThat(result).isEqualTo(VALID_HASH)
    }

    @Test
    fun `sha256sum format - uppercase hash is lowercased`() {
        val input = "$UPPER_HASH  EmuTran-arm64.apk"
        val result = parseSha256SidecarBody(input)
        assertThat(result).isEqualTo(UPPER_HASH.lowercase())
    }

    @Test
    fun `sha256sum format with newline at end is handled`() {
        val input = "$VALID_HASH  file.apk\n"
        val result = parseSha256SidecarBody(input)
        assertThat(result).isEqualTo(VALID_HASH)
    }

    // ── Error cases → null ────────────────────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertThat(parseSha256SidecarBody("")).isNull()
    }

    @Test
    fun `blank whitespace-only string returns null`() {
        assertThat(parseSha256SidecarBody("   \n\t  ")).isNull()
    }

    @Test
    fun `hash shorter than 64 chars returns null`() {
        // 63 lowercase hex chars
        assertThat(parseSha256SidecarBody("a".repeat(63))).isNull()
    }

    @Test
    fun `hash longer than 64 chars returns null`() {
        // 65 lowercase hex chars — first token has length 65, not 64
        assertThat(parseSha256SidecarBody("a".repeat(65))).isNull()
    }

    @Test
    fun `non-hex characters in token returns null`() {
        // Replace one char with 'g' which is not in [0-9a-fA-F]
        val nonHex = "g" + "a".repeat(63)
        assertThat(parseSha256SidecarBody(nonHex)).isNull()
    }

    @Test
    fun `plain filename without hash returns null`() {
        assertThat(parseSha256SidecarBody("filename.apk")).isNull()
    }

    @Test
    fun `error text like 404 not found returns null`() {
        assertThat(parseSha256SidecarBody("404 Not Found")).isNull()
    }

    @Test
    fun `html content returns null`() {
        assertThat(parseSha256SidecarBody("<html><body>Error 404</body></html>")).isNull()
    }

    // ── Leading / trailing whitespace ─────────────────────────────────────────

    @Test
    fun `leading whitespace is stripped before parsing`() {
        val result = parseSha256SidecarBody("   $VALID_HASH")
        assertThat(result).isEqualTo(VALID_HASH)
    }

    @Test
    fun `trailing whitespace is stripped before parsing`() {
        val result = parseSha256SidecarBody("$VALID_HASH   ")
        assertThat(result).isEqualTo(VALID_HASH)
    }

    @Test
    fun `leading and trailing whitespace with sha256sum format`() {
        val result = parseSha256SidecarBody("  $VALID_HASH  file.apk  ")
        assertThat(result).isEqualTo(VALID_HASH)
    }

    // ── Real-world fixture ─────────────────────────────────────────────────────

    @Test
    fun `realistic sha256sum output line is parsed correctly`() {
        // Matches output of: sha256sum EmuTran-arm64-v0.3.0.apk
        val realHash = "d41d8cd98f00b204e9800998ecf8427e" + "d41d8cd98f00b204e9800998ecf8427e"
        // That's 64 chars: two copies of 32-char MD5 lookalike (still all hex)
        val input = "$realHash  EmuTran-arm64-v0.3.0.apk"
        val result = parseSha256SidecarBody(input)
        assertThat(result).isEqualTo(realHash.lowercase())
    }
}
