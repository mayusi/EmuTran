package io.github.mayusi.emutran.data.manifest

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test

/**
 * Exercises [ObtainiumPackParser.classifySource] and [ObtainiumPackParser.systemFor]
 * **indirectly** via the public [ObtainiumPackParser.parseJson] API.
 *
 * Both target functions are private, so we cannot call them directly (and we must
 * not change visibility — another agent owns that file). Instead we craft minimal
 * synthetic JSON strings whose entries exercise every branch of the two heuristics,
 * then assert on the resulting [AppEntry.source] / [AppEntry.system] fields.
 *
 * ## Coverage achieved
 *
 * classifySource:
 *   - overrideSource = "GitHub"  → GITHUB
 *   - overrideSource = "Gitea"   → GITEA
 *   - overrideSource = "Codeberg"→ GITEA
 *   - overrideSource = "HTML"    → HTML_SCRAPE
 *   - overrideSource unknown     → UNKNOWN
 *   - no override, github.com URL          → GITHUB
 *   - no override, git.eden-emu.dev URL    → GITEA
 *   - no override, codeberg.org URL        → GITEA
 *   - no override, plain http URL          → HTML_SCRAPE
 *   - no override, non-http scheme         → UNKNOWN
 *
 * systemFor (collision-risk keywords noted):
 *   - "driver" in key            → DRIVERS   (not PLAYSTATION despite other words)
 *   - "adreno-tools" / "purple-turnip" → DRIVERS
 *   - "retroarch" in key         → RETRO
 *   - "dolphin" in key           → NINTENDO_CONSOLE
 *   - "cemu" / "eden" / "yuzu"   → NINTENDO_CONSOLE
 *   - "azahar" / "citra" / "melonds" → NINTENDO_HANDHELD
 *   - "duckstation" in key       → PLAYSTATION
 *   - "ppsspp" in key            → PLAYSTATION
 *   - "play" substring collision: "play" alone → PLAYSTATION, not DRIVERS
 *   - "driver" collision: "nethersx2-driver-test" → DRIVERS (driver wins over PS keywords)
 *   - "flycast" in key           → SEGA
 *   - "winlator" / "scummvm"     → PC_WINDOWS
 *   - "moonlight" / "obtainium"  → UTILITY
 *   - "es-de" / "pegasus"        → FRONTEND
 *   - unknown → OTHER
 */
class ObtainiumPackParserClassifyTest {

    private val parser = ObtainiumPackParser(
        context = mockk(relaxed = true),
        httpCache = mockk(relaxed = true),
        okHttpClient = mockk(relaxed = true),
        manifestDiffStore = mockk(relaxed = true),
    )

    // ── JSON builder helpers ──────────────────────────────────────────────────

    /**
     * Build a minimal valid Obtainium pack JSON with a single app entry.
     * [additionalSettings] defaults to an empty object so all fields use
     * [AppAdditionalSettings] defaults (no apkFilter, autoArch=true, etc.).
     */
    private fun singleEntryJson(
        id: String,
        url: String,
        name: String,
        overrideSource: String? = null,
        additionalSettings: String = "{}",
    ): String {
        // additionalSettings is a JSON string-within-JSON; escape inner quotes.
        val escapedSettings = additionalSettings.replace("\"", "\\\"")
        val overridePart = if (overrideSource != null) {
            ""","overrideSource":"$overrideSource""""
        } else {
            ""
        }
        return """
            {
              "apps": [
                {
                  "id": "$id",
                  "url": "$url",
                  "author": "test",
                  "name": "$name",
                  "additionalSettings": "$escapedSettings",
                  "categories": []
                  $overridePart
                }
              ]
            }
        """.trimIndent()
    }

    /** Parse a single-entry JSON and return the one resulting [AppEntry]. */
    private fun parseOne(
        id: String,
        url: String,
        name: String,
        overrideSource: String? = null,
        additionalSettings: String = "{}",
    ): AppEntry {
        val json = singleEntryJson(id, url, name, overrideSource, additionalSettings)
        val entries = parser.parseJson(json)
        assertThat(entries).hasSize(1)
        return entries.first()
    }

    // ── classifySource: overrideSource wins ───────────────────────────────────

    @Test
    fun `overrideSource GitHub maps to GITHUB regardless of URL`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://not-a-github-url.example.com/repo",
            name = "Example",
            overrideSource = "GitHub",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITHUB)
    }

    @Test
    fun `overrideSource Gitea maps to GITEA`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://github.com/example/repo",   // URL says GitHub but override wins
            name = "Example",
            overrideSource = "Gitea",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITEA)
    }

    @Test
    fun `overrideSource Codeberg maps to GITEA`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://example.com",
            name = "Example",
            overrideSource = "Codeberg",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITEA)
    }

    @Test
    fun `overrideSource HTML maps to HTML_SCRAPE`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://github.com/example/repo",
            name = "Example",
            overrideSource = "HTML",
        )
        assertThat(entry.source).isEqualTo(SourceKind.HTML_SCRAPE)
    }

    @Test
    fun `unknown overrideSource maps to UNKNOWN`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://github.com/example/repo",
            name = "Example",
            overrideSource = "ftp",
        )
        assertThat(entry.source).isEqualTo(SourceKind.UNKNOWN)
    }

    // ── classifySource: URL-based inference (no override) ────────────────────

    @Test
    fun `github com URL without override maps to GITHUB`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://github.com/foo/bar",
            name = "Example",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITHUB)
    }

    @Test
    fun `git eden-emu dev URL maps to GITEA`() {
        val entry = parseOne(
            id = "dev.eden.eden_emulator",
            url = "https://git.eden-emu.dev/eden-emu/eden",
            name = "Eden",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITEA)
    }

    @Test
    fun `codeberg org URL maps to GITEA`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://codeberg.org/user/repo",
            name = "Example",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITEA)
    }

    @Test
    fun `plain http URL without github or gitea maps to HTML_SCRAPE`() {
        val entry = parseOne(
            id = "com.example",
            url = "https://dolphin-emu.org/download",
            name = "Example",
        )
        assertThat(entry.source).isEqualTo(SourceKind.HTML_SCRAPE)
    }

    @Test
    fun `non-http scheme URL maps to UNKNOWN`() {
        val entry = parseOne(
            id = "com.example",
            url = "ftp://example.com/repo",
            name = "Example",
        )
        assertThat(entry.source).isEqualTo(SourceKind.UNKNOWN)
    }

    // ── systemFor: DRIVERS ────────────────────────────────────────────────────

    @Test
    fun `driver keyword in name maps to DRIVERS system`() {
        val entry = parseOne(
            id = "com.example.driver",
            url = "https://github.com/example/gpu-driver",
            name = "GPU Driver",
        )
        assertThat(entry.system).isEqualTo(SystemTag.DRIVERS)
    }

    @Test
    fun `purple-turnip in name maps to DRIVERS`() {
        val entry = parseOne(
            id = "com.example.turnip",
            url = "https://github.com/K11MCH1/purple-turnip",
            name = "Purple Turnip Driver",
        )
        assertThat(entry.system).isEqualTo(SystemTag.DRIVERS)
    }

    @Test
    fun `adreno-tools in id maps to DRIVERS`() {
        val entry = parseOne(
            id = "com.example.adreno-tools",
            url = "https://github.com/K11MCH1/AdrenoToolsDrivers",
            name = "Adreno Tools",
        )
        assertThat(entry.system).isEqualTo(SystemTag.DRIVERS)
    }

    // ── systemFor: collision — 'play' vs PLAYSTATION ─────────────────────────

    @Test
    fun `play substring alone maps to PLAYSTATION not DRIVERS`() {
        // 'play' must land in PLAYSTATION, not DRIVERS (which lacks 'play').
        // This verifies the when-branch order is correct.
        val entry = parseOne(
            id = "com.example.play",
            url = "https://github.com/example/play-emu",
            name = "Play Emulator",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PLAYSTATION)
    }

    @Test
    fun `driver wins over playstation keyword when both present in key`() {
        // 'driver' appears in the DRIVERS branch which is checked FIRST.
        // Even if 'play' is also present, DRIVERS should win.
        val entry = parseOne(
            id = "com.example.playstation-driver",
            url = "https://github.com/example/ps-driver",
            name = "PlayStation Driver Pack",
        )
        assertThat(entry.system).isEqualTo(SystemTag.DRIVERS)
    }

    // ── systemFor: RETRO ─────────────────────────────────────────────────────

    @Test
    fun `retroarch in name maps to RETRO`() {
        val entry = parseOne(
            id = "com.retroarch.aarch64",
            url = "https://buildbot.libretro.com/stable/",
            name = "RetroArch",
        )
        assertThat(entry.system).isEqualTo(SystemTag.RETRO)
    }

    // ── systemFor: NINTENDO_CONSOLE ───────────────────────────────────────────

    @Test
    fun `dolphin in name maps to NINTENDO_CONSOLE`() {
        val entry = parseOne(
            id = "org.dolphinemu.dolphinemu",
            url = "https://github.com/dolphin-emu/dolphin",
            name = "Dolphin",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_CONSOLE)
    }

    @Test
    fun `eden in id maps to NINTENDO_CONSOLE`() {
        val entry = parseOne(
            id = "dev.eden.eden_emulator",
            url = "https://git.eden-emu.dev/eden-emu/eden",
            name = "Eden",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_CONSOLE)
    }

    @Test
    fun `cemu in id maps to NINTENDO_CONSOLE`() {
        val entry = parseOne(
            id = "info.cemu.cemu",
            url = "https://github.com/SSimco/Cemu",
            name = "Cemu",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_CONSOLE)
    }

    @Test
    fun `yuzu in name maps to NINTENDO_CONSOLE`() {
        val entry = parseOne(
            id = "org.yuzu_emu.yuzu",
            url = "https://github.com/yuzu-emu/yuzu",
            name = "Yuzu",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_CONSOLE)
    }

    // ── systemFor: NINTENDO_HANDHELD ──────────────────────────────────────────

    @Test
    fun `azahar in id maps to NINTENDO_HANDHELD`() {
        val entry = parseOne(
            id = "org.azahar_emu.azahar",
            url = "https://github.com/azahar-emu/azahar",
            name = "Azahar",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_HANDHELD)
    }

    @Test
    fun `melonds in id maps to NINTENDO_HANDHELD`() {
        val entry = parseOne(
            id = "me.magnum.melonds",
            url = "https://github.com/MelonDS/melonDS",
            name = "MelonDS",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_HANDHELD)
    }

    @Test
    fun `citra in name maps to NINTENDO_HANDHELD`() {
        val entry = parseOne(
            id = "org.citra_emu.citra",
            url = "https://github.com/PabloMK7/citra",
            name = "Citra",
        )
        assertThat(entry.system).isEqualTo(SystemTag.NINTENDO_HANDHELD)
    }

    // ── systemFor: PLAYSTATION ────────────────────────────────────────────────

    @Test
    fun `duckstation in name maps to PLAYSTATION`() {
        val entry = parseOne(
            id = "com.github.stenzek.duckstation",
            url = "https://github.com/stenzek/duckstation",
            name = "DuckStation",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PLAYSTATION)
    }

    @Test
    fun `ppsspp in name maps to PLAYSTATION`() {
        val entry = parseOne(
            id = "org.ppsspp.ppsspp",
            url = "https://ppsspp.org/downloads",
            name = "PPSSPP",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PLAYSTATION)
    }

    @Test
    fun `nethersx2 in id maps to PLAYSTATION`() {
        val entry = parseOne(
            id = "xyz.aethersx2.android",
            url = "https://github.com/Trixarian/NetherSX2-classic",
            name = "NetherSX2",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PLAYSTATION)
    }

    // ── systemFor: SEGA ───────────────────────────────────────────────────────

    @Test
    fun `flycast in name maps to SEGA`() {
        val entry = parseOne(
            id = "com.flycast.emulator",
            url = "https://github.com/flyinghead/flycast",
            name = "Flycast",
        )
        assertThat(entry.system).isEqualTo(SystemTag.SEGA)
    }

    @Test
    fun `redream in name maps to SEGA`() {
        val entry = parseOne(
            id = "io.recompiled.redream",
            url = "https://github.com/example/redream",
            name = "Redream",
        )
        assertThat(entry.system).isEqualTo(SystemTag.SEGA)
    }

    // ── systemFor: PC_WINDOWS ─────────────────────────────────────────────────

    @Test
    fun `winlator in id maps to PC_WINDOWS`() {
        val entry = parseOne(
            id = "com.winlator",
            url = "https://github.com/brunodev85/winlator",
            name = "Winlator",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PC_WINDOWS)
    }

    @Test
    fun `scummvm in name maps to PC_WINDOWS`() {
        val entry = parseOne(
            id = "org.scummvm.scummvm",
            url = "https://github.com/scummvm/scummvm",
            name = "ScummVM",
        )
        assertThat(entry.system).isEqualTo(SystemTag.PC_WINDOWS)
    }

    // ── systemFor: UTILITY ────────────────────────────────────────────────────

    @Test
    fun `moonlight in name maps to UTILITY`() {
        val entry = parseOne(
            id = "com.limelight",
            url = "https://github.com/moonlight-stream/moonlight-android",
            name = "Moonlight",
        )
        assertThat(entry.system).isEqualTo(SystemTag.UTILITY)
    }

    @Test
    fun `obtainium in name maps to UTILITY`() {
        val entry = parseOne(
            id = "dev.imranr.obtainium",
            url = "https://github.com/ImranR98/Obtainium",
            name = "Obtainium",
        )
        assertThat(entry.system).isEqualTo(SystemTag.UTILITY)
    }

    // ── systemFor: FRONTEND ───────────────────────────────────────────────────

    @Test
    fun `es-de in name maps to FRONTEND`() {
        val entry = parseOne(
            id = "org.es_de.frontend",
            url = "https://github.com/ES-DE/emulationstation",
            name = "ES-DE",
        )
        assertThat(entry.system).isEqualTo(SystemTag.FRONTEND)
    }

    @Test
    fun `pegasus in name maps to FRONTEND`() {
        val entry = parseOne(
            id = "org.pegasus_frontend.android",
            url = "https://github.com/mmatyas/pegasus-frontend",
            name = "Pegasus",
        )
        assertThat(entry.system).isEqualTo(SystemTag.FRONTEND)
    }

    @Test
    fun `gamenative in id maps to FRONTEND`() {
        val entry = parseOne(
            id = "io.github.mayusi.gamenative",
            url = "https://github.com/mayusi/gamenative",
            name = "GameNative",
        )
        assertThat(entry.system).isEqualTo(SystemTag.FRONTEND)
    }

    // ── systemFor: OTHER (fallthrough) ────────────────────────────────────────

    @Test
    fun `unrecognized name and id maps to OTHER`() {
        val entry = parseOne(
            id = "com.example.unknownemu",
            url = "https://github.com/example/unknownemu",
            name = "XYZ Obscure Emulator",
        )
        assertThat(entry.system).isEqualTo(SystemTag.OTHER)
    }

    // ── Combined: source + system from same entry ─────────────────────────────

    @Test
    fun `github URL with flycast name yields GITHUB source and SEGA system`() {
        val entry = parseOne(
            id = "com.flycast.emulator",
            url = "https://github.com/flyinghead/flycast",
            name = "Flycast",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITHUB)
        assertThat(entry.system).isEqualTo(SystemTag.SEGA)
    }

    @Test
    fun `codeberg URL with driver name yields GITEA source and DRIVERS system`() {
        val entry = parseOne(
            id = "com.example.gpu-driver",
            url = "https://codeberg.org/user/gpu-driver",
            name = "GPU Driver Releases",
        )
        assertThat(entry.source).isEqualTo(SourceKind.GITEA)
        assertThat(entry.system).isEqualTo(SystemTag.DRIVERS)
    }

    // ── parseJson: multi-entry JSON with mixed sources ────────────────────────

    @Test
    fun `multi-entry json with github and html sources parsed correctly`() {
        val json = """
            {
              "apps": [
                {
                  "id": "com.flycast.emulator",
                  "url": "https://github.com/flyinghead/flycast",
                  "author": "flyinghead",
                  "name": "Flycast",
                  "additionalSettings": "{}",
                  "categories": []
                },
                {
                  "id": "org.ppsspp.ppsspp",
                  "url": "https://ppsspp.org/downloads",
                  "author": "hrydgard",
                  "name": "PPSSPP",
                  "additionalSettings": "{}",
                  "categories": []
                }
              ]
            }
        """.trimIndent()

        val entries = parser.parseJson(json)
        assertThat(entries).hasSize(2)

        val flycast = entries.first { it.name == "Flycast" }
        assertThat(flycast.source).isEqualTo(SourceKind.GITHUB)
        assertThat(flycast.system).isEqualTo(SystemTag.SEGA)

        val ppsspp = entries.first { it.name == "PPSSPP" }
        assertThat(ppsspp.source).isEqualTo(SourceKind.HTML_SCRAPE)
        assertThat(ppsspp.system).isEqualTo(SystemTag.PLAYSTATION)
    }
}
