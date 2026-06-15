package io.github.mayusi.emutran.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure (non-Compose) parts of MarkdownText.kt:
 *   - [isAllowedLinkScheme]  — security allowlist (bumped to internal)
 *   - [parse]               — block-level Markdown parser (bumped to internal)
 *
 * [parseInline] builds Compose [AnnotatedString] objects that require
 * EmuTones color constants backed by Compose Color — these are NOT testable
 * from a JVM unit test without Robolectric.  Those tests are intentionally
 * omitted; the inline parser is exercised indirectly in the block-parser tests
 * for headings/bullets where the block text content is checked.
 *
 * All tests are deterministic pure-Kotlin — no Android framework, no mocks.
 */
class MarkdownTextTest {

    // ── isAllowedLinkScheme ───────────────────────────────────────────────────

    @Test
    fun `https URL is allowed`() {
        assertThat(isAllowedLinkScheme("https://example.com")).isTrue()
    }

    @Test
    fun `http URL is allowed`() {
        assertThat(isAllowedLinkScheme("http://example.com")).isTrue()
    }

    @Test
    fun `HTTPS uppercase scheme is allowed case-insensitive`() {
        assertThat(isAllowedLinkScheme("HTTPS://example.com")).isTrue()
    }

    @Test
    fun `HTTP uppercase scheme is allowed case-insensitive`() {
        assertThat(isAllowedLinkScheme("HTTP://example.com")).isTrue()
    }

    @Test
    fun `javascript scheme is rejected`() {
        assertThat(isAllowedLinkScheme("javascript:alert(1)")).isFalse()
    }

    @Test
    fun `JAVASCRIPT uppercase scheme is rejected`() {
        assertThat(isAllowedLinkScheme("JAVASCRIPT:alert(1)")).isFalse()
    }

    @Test
    fun `intent scheme is rejected`() {
        assertThat(isAllowedLinkScheme("intent://example.com#Intent;scheme=https;end")).isFalse()
    }

    @Test
    fun `file scheme is rejected`() {
        assertThat(isAllowedLinkScheme("file:///etc/passwd")).isFalse()
    }

    @Test
    fun `market scheme is rejected`() {
        assertThat(isAllowedLinkScheme("market://details?id=com.example")).isFalse()
    }

    @Test
    fun `data scheme is rejected`() {
        assertThat(isAllowedLinkScheme("data:text/html,<script>alert(1)</script>")).isFalse()
    }

    @Test
    fun `tel scheme is rejected`() {
        assertThat(isAllowedLinkScheme("tel:+1234567890")).isFalse()
    }

    @Test
    fun `mailto scheme is rejected`() {
        assertThat(isAllowedLinkScheme("mailto:user@example.com")).isFalse()
    }

    @Test
    fun `empty string is rejected`() {
        assertThat(isAllowedLinkScheme("")).isFalse()
    }

    @Test
    fun `blank string with leading whitespace still matched correctly`() {
        // Leading whitespace is stripped by trimStart in isAllowedLinkScheme
        assertThat(isAllowedLinkScheme("  https://example.com")).isTrue()
    }

    @Test
    fun `leading whitespace before javascript is still rejected`() {
        // trimStart exposes "javascript:" — still rejected
        assertThat(isAllowedLinkScheme("  javascript:alert(1)")).isFalse()
    }

    // ── parse — empty / blank input ───────────────────────────────────────────

    @Test
    fun `empty string returns empty list`() {
        assertThat(parse("")).isEmpty()
    }

    @Test
    fun `blank whitespace-only string returns empty list`() {
        assertThat(parse("   \n  \t  ")).isEmpty()
    }

    // ── parse — headings ──────────────────────────────────────────────────────

    @Test
    fun `h1 heading parsed correctly`() {
        val blocks = parse("# Hello World")
        assertThat(blocks).hasSize(1)
        val h = blocks[0] as MdBlock.Heading
        assertThat(h.level).isEqualTo(1)
        assertThat(h.text).isEqualTo("Hello World")
    }

    @Test
    fun `h2 heading parsed correctly`() {
        val blocks = parse("## Section Two")
        assertThat(blocks).hasSize(1)
        val h = blocks[0] as MdBlock.Heading
        assertThat(h.level).isEqualTo(2)
        assertThat(h.text).isEqualTo("Section Two")
    }

    @Test
    fun `h3 heading parsed correctly`() {
        val blocks = parse("### Sub-section")
        assertThat(blocks).hasSize(1)
        val h = blocks[0] as MdBlock.Heading
        assertThat(h.level).isEqualTo(3)
        assertThat(h.text).isEqualTo("Sub-section")
    }

    @Test
    fun `h4 heading clamped to level 3`() {
        // HEADING_RE allows #{1,4} but coerceAtMost(3) clamps the level
        val blocks = parse("#### Deep heading")
        assertThat(blocks).hasSize(1)
        val h = blocks[0] as MdBlock.Heading
        assertThat(h.level).isEqualTo(3)
    }

    // ── parse — bullets ───────────────────────────────────────────────────────

    @Test
    fun `dash bullet parsed as depth 0`() {
        val blocks = parse("- Item one")
        assertThat(blocks).hasSize(1)
        val b = blocks[0] as MdBlock.Bullet
        assertThat(b.depth).isEqualTo(0)
        assertThat(b.text).isEqualTo("Item one")
    }

    @Test
    fun `asterisk bullet parsed as depth 0`() {
        val blocks = parse("* Item two")
        assertThat(blocks).hasSize(1)
        val b = blocks[0] as MdBlock.Bullet
        assertThat(b.depth).isEqualTo(0)
    }

    @Test
    fun `plus bullet parsed as depth 0`() {
        val blocks = parse("+ Item three")
        assertThat(blocks).hasSize(1)
        val b = blocks[0] as MdBlock.Bullet
        assertThat(b.depth).isEqualTo(0)
    }

    @Test
    fun `two-space indent bullet is depth 1`() {
        val blocks = parse("  - Nested item")
        assertThat(blocks).hasSize(1)
        val b = blocks[0] as MdBlock.Bullet
        assertThat(b.depth).isEqualTo(1)
    }

    @Test
    fun `four-space indent bullet is depth 2`() {
        val blocks = parse("    - Deep nested item")
        assertThat(blocks).hasSize(1)
        val b = blocks[0] as MdBlock.Bullet
        assertThat(b.depth).isEqualTo(2)
    }

    // ── parse — numbered list ─────────────────────────────────────────────────

    @Test
    fun `numbered list item parsed correctly`() {
        val blocks = parse("1. First item")
        assertThat(blocks).hasSize(1)
        val n = blocks[0] as MdBlock.Numbered
        assertThat(n.label).isEqualTo("1.")
        assertThat(n.text).isEqualTo("First item")
    }

    @Test
    fun `multi-digit numbered list item parsed correctly`() {
        val blocks = parse("42. Forty-second item")
        assertThat(blocks).hasSize(1)
        val n = blocks[0] as MdBlock.Numbered
        assertThat(n.label).isEqualTo("42.")
    }

    // ── parse — blockquote ────────────────────────────────────────────────────

    @Test
    fun `blockquote parsed correctly`() {
        val blocks = parse("> This is a quote")
        assertThat(blocks).hasSize(1)
        val q = blocks[0] as MdBlock.Blockquote
        assertThat(q.text).isEqualTo("This is a quote")
    }

    // ── parse — horizontal rules ──────────────────────────────────────────────

    @Test
    fun `three dashes produces Rule block`() {
        val blocks = parse("---")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(MdBlock.Rule::class.java)
    }

    @Test
    fun `three asterisks produces Rule block`() {
        val blocks = parse("***")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(MdBlock.Rule::class.java)
    }

    @Test
    fun `three underscores produces Rule block`() {
        val blocks = parse("___")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(MdBlock.Rule::class.java)
    }

    // ── parse — fenced code blocks ────────────────────────────────────────────

    @Test
    fun `fenced code block is parsed as FencedCode`() {
        val md = """
            ```
            val x = 1
            println(x)
            ```
        """.trimIndent()
        val blocks = parse(md)
        assertThat(blocks).hasSize(1)
        val code = blocks[0] as MdBlock.FencedCode
        assertThat(code.content).contains("val x = 1")
        assertThat(code.content).contains("println(x)")
    }

    @Test
    fun `unclosed fenced code block emits FencedCode with accumulated lines`() {
        // Malformed input: no closing fence — should NOT throw and should emit content
        val md = "```\nval x = 1"
        val blocks = parse(md)
        // Should emit a FencedCode with accumulated content rather than crashing
        assertThat(blocks).isNotEmpty()
        val code = blocks[0] as MdBlock.FencedCode
        assertThat(code.content).contains("val x = 1")
    }

    // ── parse — gap / blank-line handling ─────────────────────────────────────

    @Test
    fun `single blank line between paragraphs produces one Gap`() {
        val md = "First paragraph\n\nSecond paragraph"
        val blocks = parse(md)
        assertThat(blocks.any { it is MdBlock.Gap }).isTrue()
        // Adjacent blank lines are deduplicated — still only one Gap
        val gapCount = blocks.count { it is MdBlock.Gap }
        assertThat(gapCount).isEqualTo(1)
    }

    @Test
    fun `multiple consecutive blank lines collapse to one Gap`() {
        val md = "A\n\n\n\nB"
        val blocks = parse(md)
        val gapCount = blocks.count { it is MdBlock.Gap }
        assertThat(gapCount).isEqualTo(1)
    }

    @Test
    fun `leading and trailing gaps are trimmed`() {
        val md = "\n\nA paragraph\n\n"
        val blocks = parse(md)
        assertThat(blocks.first()).isNotInstanceOf(MdBlock.Gap::class.java)
        assertThat(blocks.last()).isNotInstanceOf(MdBlock.Gap::class.java)
    }

    // ── parse — plain paragraph ───────────────────────────────────────────────

    @Test
    fun `plain text line becomes Paragraph block`() {
        val blocks = parse("Just a plain sentence.")
        assertThat(blocks).hasSize(1)
        val p = blocks[0] as MdBlock.Paragraph
        assertThat(p.text).isEqualTo("Just a plain sentence.")
    }

    // ── parse — mixed content ─────────────────────────────────────────────────

    @Test
    fun `mixed heading bullet paragraph parses in order`() {
        val md = """
            # Title
            - Bullet
            Plain text
        """.trimIndent()
        val blocks = parse(md)
        assertThat(blocks[0]).isInstanceOf(MdBlock.Heading::class.java)
        assertThat(blocks[1]).isInstanceOf(MdBlock.Bullet::class.java)
        assertThat(blocks[2]).isInstanceOf(MdBlock.Paragraph::class.java)
    }

    @Test
    fun `realistic release note does not throw`() {
        // Mirrors the preview markdown in MarkdownText.kt
        val md = """
            # EmuTran v0.3.0

            ## What's new

            - **Silent install** no longer requires a rooted device
            - Fixed crash on `arm32` devices
              - Regression introduced in v0.2.1
            - [View full diff](https://github.com/mayusi/EmuTran/compare/v0.2.0...v0.3.0)
            - Package com.foo_bar_baz should not be italic

            ---

            ## Bug fixes

            1. Download progress bar now shows correct percentage
            2. *Shizuku* connection is re-checked on resume

            > Note: users on Android 10 may need to re-grant Shizuku permission.

            ```
            val x = "hello"
            println(x)
            ```
        """.trimIndent()

        // Must not throw under any circumstances
        val blocks = runCatching { parse(md) }.getOrNull()
        assertThat(blocks).isNotNull()
        assertThat(blocks!!).isNotEmpty()
    }

    @Test
    fun `extremely malformed input does not throw`() {
        val weirdInputs = listOf(
            "****",          // empty bold
            "**",            // lone bold delimiter
            "__",            // lone underline delimiter
            "```",           // lone fence
            "[link]()",      // empty URL
            "[](url)",       // empty label
            " ",  // null + SOH control chars
            "a".repeat(10_000), // very long line
        )
        for (input in weirdInputs) {
            val result = runCatching { parse(input) }
            assertThat(result.isSuccess)
                .isTrue()
        }
    }
}
