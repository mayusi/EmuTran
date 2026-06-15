package io.github.mayusi.emutran.data.manifest

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Exercises the parser against the real bundled JSON, not a hand-rolled
 * fixture. That way the test breaks the moment upstream changes a field
 * name in a way we'd need to react to.
 */
class ObtainiumPackParserTest {

    // We need a parser instance but parseJson() doesn't touch Context/network,
    // so mocked dependencies are fine — saves us pulling in Robolectric and
    // avoiding real network calls.
    private val parser = ObtainiumPackParser(
        context = mockk(relaxed = true),
        httpCache = mockk(relaxed = true),
        okHttpClient = mockk(relaxed = true),
        manifestDiffStore = mockk(relaxed = true),
    )

    private fun loadResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("manifest/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `standard manifest parses without crashing`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        assertThat(entries).isNotEmpty()
    }

    @Test
    fun `dual-screen manifest parses without crashing`() {
        val entries = parser.parseJson(loadResource("dual-screen.json"))
        assertThat(entries).isNotEmpty()
    }

    @Test
    fun `every entry has a non-blank id and name`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        val malformed = entries.filter { it.id.isBlank() || it.name.isBlank() }
        assertThat(malformed).isEmpty()
    }

    @Test
    fun `at least one entry is recognized as GitHub-sourced`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        assertThat(entries.any { it.source == SourceKind.GITHUB }).isTrue()
    }

    @Test
    fun `Flycast is in the standard manifest as GitHub source`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        val flycast = entries.firstOrNull { it.name.equals("Flycast", ignoreCase = true) }
        assertThat(flycast).isNotNull()
        assertThat(flycast!!.source).isEqualTo(SourceKind.GITHUB)
        assertThat(flycast.sourceUrl).contains("github.com/flyinghead/flycast")
        assertThat(flycast.system).isEqualTo(SystemTag.SEGA)
    }

    @Test
    fun `Eden is recognized as Gitea source`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        val eden = entries.firstOrNull { it.name.equals("Eden", ignoreCase = true) }
        assertThat(eden).isNotNull()
        assertThat(eden!!.source).isEqualTo(SourceKind.GITEA)
    }

    @Test
    fun `DuckStation mirror is recognized as HTML scrape source`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        val ducks = entries.firstOrNull { it.name.equals("DuckStation", ignoreCase = true) }
        assertThat(ducks).isNotNull()
        assertThat(ducks!!.source).isEqualTo(SourceKind.HTML_SCRAPE)
    }

    @Test
    fun `trackOnly entries are present but flagged`() {
        // The pack includes drivers and meta entries marked trackOnly:true.
        // We don't drop them at parse time — the picker UI decides how to show them.
        val entries = parser.parseJson(loadResource("standard.json"))
        val trackOnly = entries.filter { it.trackOnly }
        assertThat(trackOnly).isNotEmpty()
    }

    @Test
    fun `at least one entry is in each major system bucket`() {
        val entries = parser.parseJson(loadResource("standard.json"))
        val systems = entries.map { it.system }.toSet()
        // We don't require every bucket — just sanity that classification
        // isn't dumping everything into OTHER.
        assertThat(systems).containsAtLeast(
            SystemTag.PLAYSTATION,
            SystemTag.NINTENDO_HANDHELD,
            SystemTag.SEGA,
        )
        // OTHER shouldn't dominate. If more than 30% of entries are OTHER,
        // our heuristic is broken. Use atMost so boundary-exact counts pass.
        val otherCount = entries.count { it.system == SystemTag.OTHER }
        assertThat(otherCount.toDouble() / entries.size).isAtMost(0.30)
    }

    @Test
    fun `NetherSX2 and NetherSX2-Turnip are distinct entries with different package ids`() {
        // Earlier blueprint suggested these conflicted; verifying that
        // the live manifest gives them different Android package ids so
        // they actually CAN coexist (no mutually-exclusive grouping
        // needed).
        val entries = parser.parseJson(loadResource("standard.json"))
        val base = entries.firstOrNull { it.id == "xyz.aethersx2.android" }
        val turnip = entries.firstOrNull { it.id == "xyz.aethersx2.tturnip" }
        assertThat(base).isNotNull()
        assertThat(turnip).isNotNull()
        assertThat(base!!.mutuallyExclusiveGroup).isNull()
        assertThat(turnip!!.mutuallyExclusiveGroup).isNull()
    }

    @Test
    fun `pre-selected recommended set is non-trivial`() {
        // Sanity check that the curated allowlist actually matches real
        // manifest ids — otherwise nothing gets pre-checked in the UI.
        val entries = parser.parseJson(loadResource("standard.json"))
        val recommended = entries.filter { it.recommended }
        assertThat(recommended.size).isAtLeast(5)
        // RetroArch should be in there.
        assertThat(recommended.any { it.name.contains("RetroArch", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `RetroArch numeric tracker id is rewritten to its real package name`() {
        // The manifest carries RetroArch under Obtainium's numeric tracker
        // id (487343354) because its source is an HTML-scrape page. The
        // parser must rewrite that to the real Android package name so
        // installed-detection and the Recommended pre-check can match.
        val entries = parser.parseJson(loadResource("standard.json"))
        val retroarch = entries.firstOrNull { it.name.contains("RetroArch", ignoreCase = true) }
        assertThat(retroarch).isNotNull()
        assertThat(retroarch!!.id).isEqualTo("com.retroarch.aarch64")
        assertThat(entries.none { it.id == "487343354" }).isTrue()
        assertThat(retroarch.recommended).isTrue()
    }
}
