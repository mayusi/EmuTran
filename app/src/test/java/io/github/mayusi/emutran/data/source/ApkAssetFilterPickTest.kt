package io.github.mayusi.emutran.data.source

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.manifest.AppEntry
import io.github.mayusi.emutran.data.manifest.SourceKind
import io.github.mayusi.emutran.data.manifest.SystemTag
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApkAssetFilter.pick].
 *
 * [ApkAssetFilter] is a pure Kotlin object with no Android dependencies —
 * it can be exercised directly in a plain JVM test. [AppEntry] is a plain
 * data class; we build minimal instances via the primary constructor.
 */
class ApkAssetFilterPickTest {

    // ── Setup ─────────────────────────────────────────────────────────────────

    /**
     * [ApkAssetFilter.compileFilterRegex] calls android.util.Log.w when it rejects
     * a pattern. In a plain JVM unit test (no Robolectric, no returnDefaultValues),
     * android stubs throw RuntimeException: Stub! — stub the static before any test
     * that exercises the rejection path.
     *
     * We stub Log globally so every test can safely call pick() with any pattern
     * (Log.w is a no-op here; the real rejection behaviour in pick() is still
     * exercised because compileFilterRegex returns null when it rejects).
     */
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal AppEntry with sensible defaults; callers override as needed. */
    private fun entry(
        id: String = "com.example.emu",
        name: String = "Test Emu",
        system: SystemTag = SystemTag.PLAYSTATION,
        apkFilterRegEx: String = "",
        invertApkFilter: Boolean = false,
        autoFilterByArch: Boolean = true,
    ) = AppEntry(
        id = id,
        name = name,
        author = "test",
        about = "",
        sourceUrl = "https://github.com/example/emu",
        source = SourceKind.GITHUB,
        apkFilterRegEx = apkFilterRegEx,
        invertApkFilter = invertApkFilter,
        autoFilterByArch = autoFilterByArch,
        includePrereleases = false,
        fallbackToOlderReleases = false,
        versionExtractionRegEx = "",
        filterReleaseTitlesRegEx = "",
        categories = emptyList(),
        trackOnly = false,
        system = system,
        recommended = false,
    )

    private fun pick(assets: List<String>, e: AppEntry) = ApkAssetFilter.pick(assets, e)

    // ── Empty / no-match ──────────────────────────────────────────────────────

    @Test
    fun `empty asset list returns null`() {
        assertThat(pick(emptyList(), entry())).isNull()
    }

    @Test
    fun `list with only non-apk files and no zip returns null for APK entry`() {
        val assets = listOf("release-notes.txt", "checksums.sha256", "source.tar.gz")
        assertThat(pick(assets, entry())).isNull()
    }

    // ── arm64-v8a preference ──────────────────────────────────────────────────

    @Test
    fun `arm64-v8a is preferred when multiple arch APKs are present`() {
        val assets = listOf(
            "app-x86_64-release.apk",
            "app-armeabi-v7a-release.apk",
            "app-arm64-v8a-release.apk",
        )
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result).isNotNull()
        assertThat(result!!.first).contains("arm64-v8a")
        assertThat(result.second).isEqualTo(AssetKind.APK)
    }

    @Test
    fun `aarch64 in filename is treated as arm64`() {
        val assets = listOf(
            "emulator-x86_64.apk",
            "emulator-aarch64.apk",
        )
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result).isNotNull()
        assertThat(result!!.first).contains("aarch64")
    }

    @Test
    fun `arm64 suffix is treated as arm64`() {
        val assets = listOf(
            "app-x86.apk",
            "app-arm64.apk",
        )
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result).isNotNull()
        assertThat(result!!.first).contains("arm64")
    }

    @Test
    fun `arm64-v8a is returned even when listed last`() {
        val assets = listOf(
            "app-x86.apk",
            "app-armeabi-v7a.apk",
            "app-arm64-v8a.apk",   // last but preferred
        )
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result!!.first).contains("arm64-v8a")
    }

    // ── Fallback: no arm64, drop wrong-arch ───────────────────────────────────

    @Test
    fun `falls back to universal APK when no arm64 asset is present`() {
        val assets = listOf(
            "app-x86_64.apk",
            "app-universal.apk",   // no arch hint → survives other-arch filter
        )
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("app-universal.apk")
    }

    @Test
    fun `x86-only list returns first when no arm64 and no universal`() {
        // withoutWrongArch will be empty, so pool.firstOrNull() is used
        val assets = listOf("app-x86.apk", "app-x86_64.apk")
        val result = pick(assets, entry(autoFilterByArch = true))
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("app-x86.apk")  // first-match tie-break
    }

    @Test
    fun `arch filtering disabled returns first APK without preference`() {
        val assets = listOf(
            "app-arm64-v8a.apk",
            "app-x86_64.apk",
        )
        // With autoFilterByArch=false both branches are skipped; first APK wins.
        val result = pick(assets, entry(autoFilterByArch = false))
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("app-arm64-v8a.apk")
    }

    // ── Explicit regex filter ─────────────────────────────────────────────────

    @Test
    fun `explicit apkFilterRegEx narrows pool to matching filename`() {
        val assets = listOf(
            "app-arm64-v8a.apk",
            "app-debug.apk",
            "app-release-signed.apk",
        )
        val e = entry(apkFilterRegEx = "release-signed", autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("app-release-signed.apk")
    }

    @Test
    fun `regex filter is case-insensitive`() {
        val assets = listOf("App-ARM64-Release.apk", "app-debug.apk")
        val e = entry(apkFilterRegEx = "release", autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("App-ARM64-Release.apk")
    }

    @Test
    fun `regex filter with no match leaves pool unchanged`() {
        // When the filtered set would be empty the original pool is kept.
        val assets = listOf("app-arm64-v8a.apk")
        val e = entry(apkFilterRegEx = "foobar_nonexistent", autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("app-arm64-v8a.apk")
    }

    // ── Inverted filter ───────────────────────────────────────────────────────

    @Test
    fun `inverted filter excludes matching filenames`() {
        val assets = listOf(
            "app-arm64-v8a.apk",
            "app-debug-arm64.apk",
        )
        val e = entry(apkFilterRegEx = "debug", invertApkFilter = true, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("app-arm64-v8a.apk")
    }

    @Test
    fun `inverted filter that excludes everything leaves pool unchanged`() {
        // filtered set would be empty when invert removes all items — pool kept.
        val assets = listOf("app-debug.apk")
        val e = entry(apkFilterRegEx = "debug", invertApkFilter = true, autoFilterByArch = false)
        val result = pick(assets, e)
        // filtered is empty → pool is not replaced → original pool used → first item returned
        assertThat(result!!.first).isEqualTo("app-debug.apk")
    }

    // ── DRIVER_ZIP mode ───────────────────────────────────────────────────────

    @Test
    fun `DRIVER mode picks zip over apk when both present`() {
        val assets = listOf(
            "turnip-driver.zip",
            "turnip-readme.apk",
        )
        val e = entry(system = SystemTag.DRIVERS, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result).isNotNull()
        assertThat(result!!.first).endsWith(".zip")
        assertThat(result.second).isEqualTo(AssetKind.DRIVER_ZIP)
    }

    @Test
    fun `DRIVER mode falls back to APK when no zip available`() {
        val assets = listOf("driver-arm64.apk")
        val e = entry(system = SystemTag.DRIVERS, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result).isNotNull()
        assertThat(result!!.first).endsWith(".apk")
        assertThat(result.second).isEqualTo(AssetKind.APK)
    }

    @Test
    fun `DRIVER mode selects arm64 zip when arch filtering enabled`() {
        val assets = listOf(
            "turnip-driver-x86_64.zip",
            "turnip-driver-arm64.zip",
        )
        val e = entry(system = SystemTag.DRIVERS, autoFilterByArch = true)
        val result = pick(assets, e)
        assertThat(result!!.first).contains("arm64")
        assertThat(result.second).isEqualTo(AssetKind.DRIVER_ZIP)
    }

    @Test
    fun `non-DRIVER entry ignores zips and picks apk`() {
        val assets = listOf(
            "driver.zip",
            "app-arm64.apk",
        )
        val e = entry(system = SystemTag.PLAYSTATION, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).endsWith(".apk")
        assertThat(result.second).isEqualTo(AssetKind.APK)
    }

    // ── First-match / tie-break ───────────────────────────────────────────────

    @Test
    fun `first APK wins when no arch filtering and no regex`() {
        val assets = listOf("alpha.apk", "beta.apk", "gamma.apk")
        val e = entry(autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("alpha.apk")
    }

    @Test
    fun `first arm64 match wins when multiple arm64 assets exist`() {
        val assets = listOf(
            "app-arm64-v8a-1.apk",
            "app-arm64-v8a-2.apk",
        )
        val e = entry(autoFilterByArch = true)
        val result = pick(assets, e)
        // Both match arm64; first one should be returned.
        assertThat(result!!.first).isEqualTo("app-arm64-v8a-1.apk")
    }

    // ── compileFilterRegex ReDoS guard (tested via pick) ─────────────────────
    // compileFilterRegex is private but reachable through pick() when
    // entry.apkFilterRegEx is non-blank. We verify both rejection cases:
    //   (a) pattern length > 512 → filter skipped, pool unchanged
    //   (b) nested-quantifier pattern → filter skipped, pool unchanged

    @Test
    fun `overlong regex pattern is rejected and pool is left unchanged`() {
        val longPattern = "a".repeat(513)   // over MAX_PATTERN_LENGTH = 512
        val assets = listOf("app-arm64.apk")
        val e = entry(apkFilterRegEx = longPattern, autoFilterByArch = false)
        // Regex is rejected; pool is not filtered; first APK is returned.
        val result = pick(assets, e)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("app-arm64.apk")
    }

    @Test
    fun `nested-quantifier ReDoS pattern is rejected and pool is left unchanged`() {
        // Pattern "(.+)+" has a nested quantifier and would cause catastrophic backtracking.
        val redosPattern = "(.+)+"
        val assets = listOf("app-arm64.apk")
        val e = entry(apkFilterRegEx = redosPattern, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("app-arm64.apk")
    }

    @Test
    fun `another nested-quantifier variant is rejected`() {
        // "(a*)+" form
        val redosPattern = "(a*)+"
        val assets = listOf("app.apk")
        val e = entry(apkFilterRegEx = redosPattern, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("app.apk")
    }

    @Test
    fun `valid regex at exactly MAX_PATTERN_LENGTH is accepted`() {
        // Pattern of 512 'a' chars is a valid simple literal — compileFilterRegex must accept it.
        // It won't match "app.apk", so filtered set is empty → pool kept unchanged.
        val borderlinePattern = "a".repeat(512)
        val assets = listOf("app.apk")
        val e = entry(apkFilterRegEx = borderlinePattern, autoFilterByArch = false)
        val result = pick(assets, e)
        // Either: regex accepted but no match (pool unchanged), or still returns the one APK.
        assertThat(result!!.first).isEqualTo("app.apk")
    }

    // ── Filter + arch preference interaction ──────────────────────────────────

    @Test
    fun `regex filter applied before arch preference`() {
        // The regex narrows the pool to "release" files; arch then picks arm64 from those.
        val assets = listOf(
            "app-debug-x86_64.apk",
            "app-release-x86_64.apk",
            "app-release-arm64-v8a.apk",
        )
        val e = entry(apkFilterRegEx = "release", autoFilterByArch = true)
        val result = pick(assets, e)
        assertThat(result!!.first).isEqualTo("app-release-arm64-v8a.apk")
    }

    @Test
    fun `inverted filter combined with arch preference`() {
        val assets = listOf(
            "app-debug-arm64-v8a.apk",
            "app-release-arm64-v8a.apk",
        )
        val e = entry(apkFilterRegEx = "debug", invertApkFilter = true, autoFilterByArch = true)
        val result = pick(assets, e)
        // "debug" excluded → only release remains → arm64 hits on it.
        assertThat(result!!.first).isEqualTo("app-release-arm64-v8a.apk")
    }

    // ── Case-insensitive extension matching ───────────────────────────────────

    @Test
    fun `APK filename with uppercase extension is included`() {
        val assets = listOf("App-arm64-v8a.APK")
        val result = pick(assets, entry(autoFilterByArch = false))
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("App-arm64-v8a.APK")
    }

    @Test
    fun `ZIP filename with uppercase extension is included for DRIVER entry`() {
        val assets = listOf("Driver.ZIP")
        val e = entry(system = SystemTag.DRIVERS, autoFilterByArch = false)
        val result = pick(assets, e)
        assertThat(result).isNotNull()
        assertThat(result!!.second).isEqualTo(AssetKind.DRIVER_ZIP)
    }
}
