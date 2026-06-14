package io.github.mayusi.emutran.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.mayusi.emutran.ui.theme.EmuTones
import io.github.mayusi.emutran.ui.theme.EmuTranTheme

// ─────────────────────────────────────────────────────────────────────────────
// MarkdownText — lightweight GitHub-flavored Markdown renderer.
//
// Designed for release-note bodies: a restricted but practical subset is
// supported without pulling in any third-party library.  Everything is
// pure Kotlin string parsing → AnnotatedString / Column of Text composables.
//
// Supported block types
//   # / ## / ###   → headings (titleLarge / titleMedium / titleSmall)
//   - / * / +      → bullet list, indent-aware (2/4 spaces = nested level)
//   N.             → numbered list (label preserved)
//   ---  ***       → horizontal rule (HorizontalDivider)
//   blank line     → vertical gap between blocks
//   > text         → blockquote (italic, secondary gray)
//   ![alt](url)    → alt text rendered plain (images skipped)
//   plain text     → bodyMedium, primary white
//
// Supported inline spans (applied inside every text block)
//   **text** / __text__   → bold
//   *text*   / _text_     → italic  (parsed after bold to avoid collision)
//   `code`                → monospace, EmuTones.container background
//   [label](url)          → bold + underlined white, clickable via UriHandler
//
// Links use the modern LinkAnnotation.Url API (no ClickableText required).
// Malformed inline markdown never throws — the raw text is shown as-is.
// Empty / blank input renders nothing.
// ─────────────────────────────────────────────────────────────────────────────

// ── Block classification ─────────────────────────────────────────────────────

private sealed interface MdBlock {
    /** `#`, `##`, `###` heading. [level] 1–3. */
    data class Heading(val level: Int, val text: String) : MdBlock
    /** Bullet list item. [depth] 0 = top-level, 1+ = nested. */
    data class Bullet(val depth: Int, val text: String) : MdBlock
    /** Numbered list item. [label] is "1.", "2.", etc. */
    data class Numbered(val label: String, val text: String) : MdBlock
    /** `>` blockquote line. */
    data class Blockquote(val text: String) : MdBlock
    /** Horizontal rule (`---` / `***`). */
    object Rule : MdBlock
    /** Vertical whitespace between paragraphs. */
    object Gap : MdBlock
    /** Fallthrough plain paragraph / sentence. */
    data class Paragraph(val text: String) : MdBlock
}

// ── Parser ────────────────────────────────────────────────────────────────────

private val HEADING_RE    = Regex("""^(#{1,4})\s+(.*)""")
private val BULLET_RE     = Regex("""^( {0,8})([-*+])\s+(.*)""")
private val NUMBERED_RE   = Regex("""^(\d+\.)\s+(.*)""")
private val BLOCKQUOTE_RE = Regex("""^>\s?(.*)""")
private val RULE_RE       = Regex("""^([-*_])\1{2,}\s*$""")
// Strip image syntax entirely; show alt text if non-empty.
private val IMAGE_RE      = Regex("""!\[([^\]]*?)]\([^)]*?\)""")

/**
 * Converts a raw Markdown string into a flat list of [MdBlock]s ready for
 * rendering.  Each line is classified exactly once; adjacent blank lines are
 * collapsed to a single [MdBlock.Gap].
 */
private fun parse(markdown: String): List<MdBlock> {
    if (markdown.isBlank()) return emptyList()

    val blocks = mutableListOf<MdBlock>()
    var lastWasGap = false

    for (rawLine in markdown.lines()) {
        val line = rawLine.trimEnd()

        // Blank line → gap (deduplicated)
        if (line.isBlank()) {
            if (!lastWasGap) {
                blocks += MdBlock.Gap
                lastWasGap = true
            }
            continue
        }
        lastWasGap = false

        // Horizontal rule
        if (RULE_RE.matches(line)) {
            blocks += MdBlock.Rule
            continue
        }

        // Heading
        val hMatch = HEADING_RE.matchEntire(line)
        if (hMatch != null) {
            val level = hMatch.groupValues[1].length.coerceAtMost(3)
            val text  = hMatch.groupValues[2].trim()
            blocks += MdBlock.Heading(level, text)
            continue
        }

        // Bullet list
        val bMatch = BULLET_RE.matchEntire(line)
        if (bMatch != null) {
            val indent = bMatch.groupValues[1].length
            val depth  = when {
                indent >= 4 -> 2
                indent >= 2 -> 1
                else        -> 0
            }
            val text = bMatch.groupValues[3].trim()
            blocks += MdBlock.Bullet(depth, text)
            continue
        }

        // Numbered list
        val nMatch = NUMBERED_RE.matchEntire(line)
        if (nMatch != null) {
            blocks += MdBlock.Numbered(nMatch.groupValues[1], nMatch.groupValues[2].trim())
            continue
        }

        // Blockquote
        val qMatch = BLOCKQUOTE_RE.matchEntire(line)
        if (qMatch != null) {
            blocks += MdBlock.Blockquote(qMatch.groupValues[1].trim())
            continue
        }

        // Plain paragraph
        blocks += MdBlock.Paragraph(line)
    }

    // Trim leading/trailing gaps
    return blocks.dropWhile { it is MdBlock.Gap }.dropLastWhile { it is MdBlock.Gap }
}

// ── Inline span parser ────────────────────────────────────────────────────────

/**
 * Converts inline Markdown in [text] into an [AnnotatedString] with [SpanStyle]s
 * applied.  Links use [LinkAnnotation.Url] so plain [Text] handles them —
 * no [androidx.compose.foundation.text.ClickableText] needed.
 *
 * Order of matching:
 *   1. Backtick inline code  (suppresses further parsing inside)
 *   2. Bold `**` / `__`     (must precede italic to avoid eating outer `*`)
 *   3. Italic `*` / `_`
 *   4. Link `[label](url)`
 *   5. Plain character
 *
 * Never throws — malformed input falls back to the raw [text] unstyled.
 */
private fun parseInline(
    text: String,
    baseColor: androidx.compose.ui.graphics.Color,
): AnnotatedString = runCatching {
    // Strip image syntax; expose alt text if present.
    val cleaned = IMAGE_RE.replace(text) { m ->
        val alt = m.groupValues[1].trim()
        if (alt.isNotEmpty()) alt else ""
    }

    buildAnnotatedString {
        var cursor = 0
        val src = cleaned

        while (cursor < src.length) {

            // ── Inline code ──────────────────────────────────────────────────
            if (src[cursor] == '`') {
                val end = src.indexOf('`', cursor + 1)
                if (end > cursor) {
                    val code = src.substring(cursor + 1, end)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = EmuTones.container,
                            color      = EmuTones.onSurface,
                        )
                    ) { append(code) }
                    cursor = end + 1
                    continue
                }
            }

            // ── Bold: **...** or __...__ ─────────────────────────────────────
            val boldDelim = when {
                src.startsWith("**", cursor) -> "**"
                src.startsWith("__", cursor) -> "__"
                else                          -> null
            }
            if (boldDelim != null) {
                val end = src.indexOf(boldDelim, cursor + 2)
                if (end > cursor) {
                    val inner = src.substring(cursor + 2, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(inner)
                    }
                    cursor = end + 2
                    continue
                }
            }

            // ── Italic: *...* or _..._ ────────────────────────────────────────
            // Only single-character delimiter (double already consumed above).
            val italicDelim = when {
                src[cursor] == '*' && !src.startsWith("**", cursor) -> "*"
                src[cursor] == '_' && !src.startsWith("__", cursor) -> "_"
                else -> null
            }
            if (italicDelim != null) {
                val end = src.indexOf(italicDelim, cursor + 1)
                if (end > cursor) {
                    val inner = src.substring(cursor + 1, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(inner)
                    }
                    cursor = end + 1
                    continue
                }
            }

            // ── Link: [label](url) ────────────────────────────────────────────
            if (src[cursor] == '[') {
                val labelEnd = src.indexOf(']', cursor + 1)
                if (labelEnd > cursor &&
                    labelEnd + 1 < src.length &&
                    src[labelEnd + 1] == '('
                ) {
                    val urlEnd = src.indexOf(')', labelEnd + 2)
                    if (urlEnd > labelEnd) {
                        val label = src.substring(cursor + 1, labelEnd)
                        val url   = src.substring(labelEnd + 2, urlEnd)
                        // LinkAnnotation.Url makes the span natively clickable in Text.
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        fontWeight     = FontWeight.Bold,
                                        textDecoration = TextDecoration.Underline,
                                        color          = EmuTones.onSurface,
                                    ),
                                ),
                            )
                        ) { append(label) }
                        cursor = urlEnd + 1
                        continue
                    }
                }
            }

            // ── Plain character ───────────────────────────────────────────────
            append(src[cursor])
            cursor++
        }
    }
}.getOrElse {
    // Malformed input — return raw text unstyled rather than crashing.
    AnnotatedString(text)
}

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Renders a subset of GitHub-flavored Markdown as a [Column] of styled
 * [Text] composables.  No external library is used — the entire parser is
 * pure Kotlin string processing with [AnnotatedString] builders.
 *
 * See the file header for the full list of supported syntax.  Unsupported
 * constructs are shown as plain text; malformed input never throws.
 *
 * Links are clickable via [LinkAnnotation.Url] embedded in the [AnnotatedString]
 * — the system [LocalUriHandler] is wired through [TextLinkStyles] so standard
 * [Text] handles the tap without a separate click handler.
 *
 * @param markdown  Raw Markdown string (e.g. a GitHub release-note body).
 *                  Empty or blank input renders nothing.
 * @param modifier  Applied to the outer [Column].
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = parse(markdown)
    if (blocks.isEmpty()) return

    // UriHandler is available in the composition but wired through
    // LinkAnnotation.Url — we don't call it manually.
    LocalUriHandler.current

    Column(modifier = modifier) {
        for (block in blocks) {
            when (block) {

                // ── Headings ─────────────────────────────────────────────────
                is MdBlock.Heading -> {
                    val style: TextStyle = when (block.level) {
                        1    -> MaterialTheme.typography.titleLarge
                        2    -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    val weight = if (block.level <= 2) FontWeight.Bold else FontWeight.SemiBold
                    Text(
                        text      = parseInline(block.text, EmuTones.onSurface),
                        style     = style,
                        fontWeight = weight,
                        color     = EmuTones.onSurface,
                    )
                }

                // ── Bullet list ───────────────────────────────────────────────
                is MdBlock.Bullet -> {
                    val startPad = if (block.depth > 0) Dimens.ItemGap * block.depth else 0.dp
                    val full = buildAnnotatedString {
                        append("•  ")
                        append(parseInline(block.text, EmuTones.onSurface))
                    }
                    Text(
                        text     = full,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = EmuTones.onSurface,
                        modifier = Modifier.padding(start = startPad),
                    )
                }

                // ── Numbered list ─────────────────────────────────────────────
                is MdBlock.Numbered -> {
                    val full = buildAnnotatedString {
                        append("${block.label}  ")
                        append(parseInline(block.text, EmuTones.onSurface))
                    }
                    Text(
                        text  = full,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmuTones.onSurface,
                    )
                }

                // ── Blockquote ────────────────────────────────────────────────
                is MdBlock.Blockquote -> {
                    Text(
                        text     = parseInline(block.text, EmuTones.onSurfaceVar),
                        style    = MaterialTheme.typography.bodyMedium
                            .copy(fontStyle = FontStyle.Italic),
                        color    = EmuTones.onSurfaceVar,
                        modifier = Modifier.padding(start = Dimens.ItemGap),
                    )
                }

                // ── Horizontal rule ───────────────────────────────────────────
                MdBlock.Rule -> {
                    HorizontalDivider(
                        color    = EmuTones.outlineDivider,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // ── Blank-line gap ────────────────────────────────────────────
                MdBlock.Gap -> {
                    Spacer(modifier = Modifier.height(Dimens.ItemGap))
                }

                // ── Plain paragraph ───────────────────────────────────────────
                is MdBlock.Paragraph -> {
                    Text(
                        text  = parseInline(block.text, EmuTones.onSurface),
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmuTones.onSurface,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Preview — eyeball the rendering in Android Studio without a device.
// Wrapped in EmuTranTheme so typography tokens resolve correctly.
// ─────────────────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000, name = "MarkdownText – release note")
@Composable
private fun PreviewMarkdownText() {
    EmuTranTheme {
        Surface(color = EmuTones.bg) {
            MarkdownText(
                markdown = """
                    # EmuTran v0.3.0

                    ## What's new

                    - **Silent install** no longer requires a rooted device
                    - Fixed crash on `arm32` devices when scanning app list
                      - Regression introduced in v0.2.1 — now resolved
                    - [View full diff](https://github.com/mayusi/EmuTran/compare/v0.2.0...v0.3.0)

                    ---

                    ## Bug fixes

                    1. Download progress bar now shows correct percentage
                    2. *Shizuku* connection is re-checked on resume, not just on launch

                    > Note: users on Android 10 may need to re-grant Shizuku permission
                    after this update.

                    Plain paragraph with **bold**, *italic*, and `inline code` all in one line.
                """.trimIndent(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}
