package io.github.mayusi.emutran.data.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Targeted unit tests for the regexes inside HtmlScrapeSource.
 * We test the regexes against real captured HTML snippets so a future
 * site redesign breaks the test instead of the install loop.
 */
class HtmlScrapeSourceTest {

    private val versionDirRegex =
        Regex("""href\s*=\s*["'](?:[^"']*?/)?(\d[\d.]*)/["']""",
            RegexOption.IGNORE_CASE)

    private val archDirRegex =
        Regex("""href\s*=\s*["'](?:[^"']*?/)?((?:arm64-v8a|arm64|aarch64|armeabi-v7a|armeabi|x86_64|x86))/["']""",
            RegexOption.IGNORE_CASE)

    private val apkLinkRegex =
        Regex("""(?:href|src)\s*=\s*["']([^"']+\.apk[^"']*)["']""",
            RegexOption.IGNORE_CASE)

    /**
     * Real markup from buildbot.libretro.com/stable/.
     * Hrefs are ABSOLUTE (/stable/1.21.0/), not relative.
     */
    @Test
    fun `RetroArch buildbot version regex picks absolute hrefs`() {
        val html = """
            <a href=".."></a>
            <a href="/stable/1.10.0/">1.10.0</a>
            <a href="/stable/1.21.0/">1.21.0</a>
            <a href="/stable/1.19.0/">1.19.0</a>
        """.trimIndent()
        val versions = versionDirRegex.findAll(html).map { it.groupValues[1] }.toList()
        assertThat(versions).containsExactly("1.10.0", "1.21.0", "1.19.0")
    }

    /**
     * The href=".." parent link must NOT be picked as a version.
     */
    @Test
    fun `version regex skips parent-dir link`() {
        val html = """<a href="..">..</a><a href="..">..</a>"""
        val versions = versionDirRegex.findAll(html).map { it.groupValues[1] }.toList()
        assertThat(versions).isEmpty()
    }

    /**
     * Relative version hrefs (Apache autoindex style) still work.
     * Both styles co-exist across the sources we scrape.
     */
    @Test
    fun `version regex still picks relative hrefs`() {
        val html = """<a href="0.42/">0.42</a><a href="0.43/">0.43</a>"""
        val versions = versionDirRegex.findAll(html).map { it.groupValues[1] }.toList()
        assertThat(versions).containsExactly("0.42", "0.43")
    }

    /**
     * Real markup from buildbot.libretro.com/stable/1.21.0/android/.
     * APKs are flat (no arch subdir) and the hrefs are absolute paths.
     */
    @Test
    fun `APK regex picks all RetroArch APKs from a flat android dir`() {
        val html = """
            <a href="/stable/1.21.0/android/RetroArch.apk">RetroArch</a>
            <a href="/stable/1.21.0/android/RetroArch_aarch64.apk">aarch64</a>
            <a href="/stable/1.21.0/android/RetroArch_ra32.apk">ra32</a>
        """.trimIndent()
        val apks = apkLinkRegex.findAll(html).map { it.groupValues[1] }.toList()
        assertThat(apks).hasSize(3)
        assertThat(apks.any { it.endsWith("RetroArch_aarch64.apk") }).isTrue()
    }

    /**
     * Arch dir regex must accept both relative and absolute href forms.
     */
    @Test
    fun `arch dir regex picks both relative and absolute hrefs`() {
        val rel = """<a href="arm64-v8a/">arm64-v8a</a>"""
        val abs = """<a href="/path/to/arm64-v8a/">arm64-v8a</a>"""
        assertThat(archDirRegex.findAll(rel).map { it.groupValues[1] }.toList())
            .containsExactly("arm64-v8a")
        assertThat(archDirRegex.findAll(abs).map { it.groupValues[1] }.toList())
            .containsExactly("arm64-v8a")
    }
}
