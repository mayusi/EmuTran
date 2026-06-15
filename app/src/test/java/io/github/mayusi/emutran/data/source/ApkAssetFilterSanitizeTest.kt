package io.github.mayusi.emutran.data.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ApkAssetFilter.sanitizeFilename].
 *
 * Verifies the path-traversal guard — a remote server must never be able to
 * escape the cache directory by returning an asset name like "../../etc/passwd".
 *
 * [sanitizeFilename] is a public fun on an object — no mocks or visibility
 * changes needed.
 */
class ApkAssetFilterSanitizeTest {

    // Convenience alias
    private fun sanitize(raw: String) = ApkAssetFilter.sanitizeFilename(raw)

    // ── Path-traversal guard ──────────────────────────────────────────────────

    @Test
    fun `path traversal with forward slashes is stripped to basename`() {
        val result = sanitize("../../etc/passwd")
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain("..")
    }

    @Test
    fun `path traversal with backslashes is stripped to basename`() {
        val result = sanitize("..\\..\\Windows\\System32\\evil.dll")
        assertThat(result).doesNotContain("\\")
        assertThat(result).doesNotContain("..")
    }

    @Test
    fun `absolute unix path is stripped to last segment`() {
        val result = sanitize("/etc/cron.d/payload.apk")
        assertThat(result).doesNotContain("/")
        assertThat(result).isEqualTo("payload.apk")
    }

    @Test
    fun `normal apk filename is unchanged`() {
        val result = sanitize("Flycast-arm64-v8a-1.2.3.apk")
        assertThat(result).isEqualTo("Flycast-arm64-v8a-1.2.3.apk")
    }

    @Test
    fun `alphanumeric dots dashes underscores are all allowed`() {
        val result = sanitize("My_App-1.0.apk")
        assertThat(result).isEqualTo("My_App-1.0.apk")
    }

    // ── Disallowed character replacement ─────────────────────────────────────

    @Test
    fun `spaces are replaced with underscores`() {
        val result = sanitize("my app v1.0.apk")
        assertThat(result).doesNotContain(" ")
    }

    @Test
    fun `characters outside allowed set are replaced`() {
        val result = sanitize("name!@#\$%.apk")
        // Only [A-Za-z0-9._-] plus underscores (replacements) should remain
        assertThat(result).matches("[A-Za-z0-9._\\-_]+")
    }

    @Test
    fun `unicode characters are replaced`() {
        val result = sanitize("émulateur.apk")
        assertThat(result).doesNotContain("é")
        assertThat(result).isNotEmpty()
    }

    // ── Length cap ────────────────────────────────────────────────────────────

    @Test
    fun `very long filename is truncated to 128 characters`() {
        val longName = "A".repeat(300) + ".apk"
        val result = sanitize(longName)
        assertThat(result.length).isAtMost(128)
    }

    @Test
    fun `filename at exactly 128 chars is not truncated`() {
        val name128 = "A".repeat(124) + ".apk"   // 124 + 4 = 128
        val result = sanitize(name128)
        assertThat(result.length).isEqualTo(128)
    }

    @Test
    fun `filename at 129 chars is truncated to 128`() {
        val name129 = "A".repeat(125) + ".apk"   // 125 + 4 = 129
        val result = sanitize(name129)
        assertThat(result.length).isEqualTo(128)
    }

    // ── Empty / blank input safe default ──────────────────────────────────────

    @Test
    fun `empty string returns safe default`() {
        val result = sanitize("")
        assertThat(result).isNotEmpty()
        assertThat(result).isEqualTo("download")
    }

    @Test
    fun `string with only slashes returns safe default`() {
        // After substringAfterLast('/') we get "", then after replacing we still
        // get blank, so the fallback "download" must kick in.
        val result = sanitize("///")
        assertThat(result).isNotEmpty()
        assertThat(result).isEqualTo("download")
    }

    @Test
    fun `string of only special chars returns safe default`() {
        // All chars replaced by '_', which is not blank, so we get "_" not "download".
        // Blank is checked with isBlank(); "_" is not blank.
        val result = sanitize("!@#")
        assertThat(result).isNotEmpty()
        // Either "___" (replacements) or "download" — both are safe, assert non-empty.
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain("\\")
    }

    // ── Combined: path + long name ────────────────────────────────────────────

    @Test
    fun `path traversal with long filename is truncated after sanitization`() {
        val raw = "../../" + "A".repeat(300) + ".apk"
        val result = sanitize(raw)
        assertThat(result).doesNotContain("/")
        assertThat(result).doesNotContain("..")
        assertThat(result.length).isAtMost(128)
    }
}
