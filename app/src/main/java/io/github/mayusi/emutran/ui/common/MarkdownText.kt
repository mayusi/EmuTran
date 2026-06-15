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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
//   ``` fence ```  → fenced code block (monospace, EmuTones.container bg)
//   ![alt](url)    → alt text rendered plain (images skipped)
//   plain text     → bodyMedium, primary white
//
// Supported inline spans (applied inside every text block)
//   **text** / __text__   → bold
//   *text*   / _text_     → italic  (parsed after bold to avoid collision)
//   `code`                → monospace, EmuTones.container background
//   [label](url)          → bold + underlined white, clickable via UriHandler
//                           ONLY http:// and https:// links are clickable;
//                           all other schemes render as plain styled text.
//
// Security: links are only made clickable for http:// and https:// URLs.
// javascript:, intent:, file:, market:, data: etc. are stripped to plain text.
//
// Links use the modern LinkAnnotation.Url API (no ClickableText required).
// LinkAnnotation.Url routes taps through the system UriHandler implicitly —
// no manual LocalUriHandler.current call is needed.
//
// Performance: parse(markdown) is wrapped in remember(markdown) and
// parseInline results are memoized per text block to avoid re-parsing on
// every recomposition (this composable lives in a bottom sheet that
// recomposes frequently during download-progress updates).
//
// Malformed inline markdown never throws — the raw text is shown as-is.
// Empty / blank input renders nothing.
// ─────────────────────────────────────────────────────────────────────────────

// ── Block classification ─────────────────────────────────────────────────────

internal sealed interface MdBlock {
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
    /** Fenced code block (``` ... ```). Content is the raw text between fences. */
    data class FencedCode(val content: String) : MdBlock
    /** Fallthrough plain paragraph / sentence. */
    data class Paragraph(val text: String) : MdBlock
}

// ── Parser ────────────────────────────────────────────────────────────────────

private val HEADING_RE    = Regex("""^(#{1,4})\s+(.*)""")
private val BULLET_RE     = Regex("""^( {0,8})([-*+])\s+(.*)""")
private val NUMBERED_RE   = Regex("""^(\d+\.)\s+(.*)""")
private val BLOCKQUOTE_RE = Regex("""^>\s?(.*)""")
private val RULE_RE       = Regex("""^([-*_])\1{2,}\s*$""")
private val FENCE_RE      = Regex("""^```""")
// Strip image syntax entirely; show alt text if non-empty.
private val IMAGE_RE      = Regex("""!\[([^\]]*?)]\([^)]*?\)""")

/**
 * Converts a raw Markdown string into a flat list of [MdBlock]s ready for
 * rendering.  Each line is classified exactly once; adjacent blank lines are
 * collapsed to a single [MdBlock.Gap].  Triple-backtick fenced code blocks
 * are accumulated into a single [MdBlock.FencedCode].
 */
internal fun parse(markdown: String): List<MdBlock> {
    if (markdown.isBlank()) return emptyList()

    val blocks = mutableListOf<MdBlock>()
    var lastWasGap = false
    var inFence = false
    val fenceLines = mutableListOf<String>()

    for (rawLine in markdown.lines()) {
        val line = rawLine.trimEnd()

        // ── Fenced code block ────────────────────────────────────────────────
        if (FENCE_RE.containsMatchIn(line)) {
            if (!inFence) {
                // Opening fence — start accumulating.
                inFence = true
                fenceLines.clear()
                lastWasGap = false
            } else {
                // Closing fence — emit accumulated content as FencedCode.
                inFence = false
                blocks += MdBlock.FencedCode(fenceLines.joinToString("\n"))
                fenceLines.clear()
                lastWasGap = false
            }
            continue
        }
        if (inFence) {
            fenceLines += line
            continue
        }

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

    // If the file ends inside a fence (malformed), emit whatever we accumulated.
    if (inFence && fenceLines.isNotEmpty()) {
        blocks += MdBlock.FencedCode(fenceLines.joinToString("\n"))
    }

    // Trim leading/trailing gaps
    return blocks.dropWhile { it is MdBlock.Gap }.dropLastWhile { it is MdBlock.Gap }
}

// ── URL scheme allowlist ──────────────────────────────────────────────────────

/**
 * Returns true if [url] has an allowed scheme for clickable links.
 * Only http:// and https:// are permitted; all other schemes
 * (javascript:, intent:, file:, market:, data:, etc.) are rejected
 * to prevent untrusted release-body markdown from dispatching harmful URIs.
 */
internal fun isAllowedLinkScheme(url: String): Boolean {
    val lower = url.trimStart()
    return lower.startsWith("https://", ignoreCase = true) ||
        lower.startsWith("http://", ignoreCase = true)
}

// ── Inline span parser ────────────────────────────────────────────────────────

/**
 * Converts inline Markdown in [text] into an [AnnotatedString] with [SpanStyle]s
 * applied.  Links use [LinkAnnotation.Url] so plain [Text] handles them —
 * no [androidx.compose.foundation.text.ClickableText] needed.
 *
 * LinkAnnotation.Url routes taps through the system UriHandler implicitly;
 * no LocalUriHandler.current call is required at the composable level.
 *
 * URL scheme allowlist (Fix 1): only http:// and https:// links become
 * clickable spans.  Any other scheme (javascript:, intent:, file:, etc.)
 * is rendered as plain styled text instead.
 *
 * Order of matching:
 *   1. Backtick inline code  (suppresses further parsing inside)
 *   2. Bold `**` / `__`     (must precede italic to avoid eating outer `*`)
 *   3. Italic `*` / `_`     (word-boundary guard for `_` to avoid mis-parsing
 *                             identifiers like com.foo_bar_baz)
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
                // Require content between backticks: end > cursor + 1
                if (end > cursor + 1) {
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
                val delimLen = boldDelim.length
                val end = src.indexOf(boldDelim, cursor + delimLen)
                // Require non-empty content between delimiters.
                if (end > cursor + delimLen) {
                    val inner = src.substring(cursor + delimLen, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        // Recursively parse inline spans inside bold.
                        append(parseInline(inner, baseColor))
                    }
                    cursor = end + delimLen
                    continue
                }
            }

            // ── Italic: *...* or _..._ ────────────────────────────────────────
            // Only single-character delimiter (double already consumed above).
            //
            // For `_`: apply a word-boundary guard so identifiers like
            // com.foo_bar_baz are not mis-parsed as italic spans.
            // Rule: the char immediately before `_` must not be alphanumeric,
            // AND the char immediately after the closing `_` must not be alphanumeric.
            // For `*`: no word-boundary guard (standard Markdown behaviour).
            val isStarItalic = src[cursor] == '*' && !src.startsWith("**", cursor)
            val isUnderItalic = src[cursor] == '_' && !src.startsWith("__", cursor)
            if (isStarItalic || isUnderItalic) {
                val italicDelim = if (isStarItalic) "*" else "_"
                val end = src.indexOf(italicDelim, cursor + 1)
                // Require non-empty content: end > cursor + 1
                if (end > cursor + 1) {
                    val valid = if (isUnderItalic) {
                        // Word-boundary guard for underscore italics.
                        val charBefore = if (cursor > 0) src[cursor - 1] else ' '
                        val charAfter  = if (end + 1 < src.length) src[end + 1] else ' '
                        !charBefore.isLetterOrDigit() && !charAfter.isLetterOrDigit()
                    } else {
                        true // `*` italics have no word-boundary restriction
                    }
                    if (valid) {
                        val inner = src.substring(cursor + 1, end)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                            // Recursively parse inline spans inside italic
                            // (handles **bold** inside _italic_ etc.).
                            append(parseInline(inner, baseColor))
                        }
                        cursor = end + 1
                        continue
                    }
                }
            }

            // ── Link: [label](url) ────────────────────────────────────────────
            if (src[cursor] == '[') {
                val labelEnd = src.indexOf(']', cursor + 1)
                if (labelEnd > cursor &&
                    labelEnd + 1 < src.length &&
                    src[labelEnd + 1] == '('
                ) {
                    // Track paren nesting when scanning for closing ')' so URLs
                    // containing ')' are handled correctly (e.g. Wikipedia links).
                    val urlStart = labelEnd + 2
                    var depth = 1
                    var i = urlStart
                    while (i < src.length && depth > 0) {
                        when (src[i]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        if (depth > 0) i++
                    }
                    val urlEnd = if (depth == 0) i else -1

                    if (urlEnd > labelEnd) {
                        val label = src.substring(cursor + 1, labelEnd)
                        val url   = src.substring(urlStart, urlEnd)

                        // FIX 1 (security): only make the link clickable for
                        // http:// and https:// schemes. javascript:, intent:,
                        // file:, market:, data:, etc. are rendered as styled
                        // plain text to prevent untrusted release-body markdown
                        // from dispatching harmful URIs via LocalUriHandler.
                        if (isAllowedLinkScheme(url)) {
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
                        } else {
                            // Disallowed scheme — render label as plain styled text,
                            // not as a clickable link, so the text is still readable
                            // but the URI cannot be dispatched.
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    color      = EmuTones.onSurface,
                                )
                            ) { append(label) }
                        }
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
}.getOrElse { t ->
    // Malformed input — return raw text unstyled rather than crashing.
    // Debug-level log to aid investigation of edge cases (stripped in release).
    android.util.Log.d("MarkdownText", "parseInline failed on: $text", t)
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
 * — the system UriHandler is wired through [TextLinkStyles] implicitly, so
 * standard [Text] handles the tap without any manual click handling.
 *
 * Only http:// and https:// URLs are made clickable (see security note in
 * [parseInline]).
 *
 * Performance: block-list parsing is wrapped in [remember](markdown) and
 * per-block inline parsing is wrapped in [remember](block.text) so neither
 * re-runs on every recomposition (important: this composable lives inside a
 * bottom sheet that recomposes on every download-progress update).
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
    // Memoize the full block-list parse so it only re-runs when markdown changes.
    val blocks = remember(markdown) { parse(markdown) }
    if (blocks.isEmpty()) return

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
                    // Memoize inline parse per block text.
                    val annotated = remember(block.text) { parseInline(block.text, EmuTones.onSurface) }
                    Text(
                        text      = annotated,
                        style     = style,
                        fontWeight = weight,
                        color     = EmuTones.onSurface,
                    )
                }

                // ── Bullet list ───────────────────────────────────────────────
                is MdBlock.Bullet -> {
                    val startPad = if (block.depth > 0) Dimens.ItemGap * block.depth else 0.dp
                    val annotated = remember(block.text) { parseInline(block.text, EmuTones.onSurface) }
                    val full = remember(annotated) {
                        buildAnnotatedString {
                            append("•  ")
                            append(annotated)
                        }
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
                    val annotated = remember(block.text) { parseInline(block.text, EmuTones.onSurface) }
                    val full = remember(block.label, annotated) {
                        buildAnnotatedString {
                            append("${block.label}  ")
                            append(annotated)
                        }
                    }
                    Text(
                        text  = full,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmuTones.onSurface,
                    )
                }

                // ── Blockquote ────────────────────────────────────────────────
                is MdBlock.Blockquote -> {
                    val annotated = remember(block.text) {
                        parseInline(block.text, EmuTones.onSurfaceVar)
                    }
                    Text(
                        text     = annotated,
                        style    = MaterialTheme.typography.bodyMedium
                            .copy(fontStyle = FontStyle.Italic),
                        color    = EmuTones.onSurfaceVar,
                        modifier = Modifier.padding(start = Dimens.ItemGap),
                    )
                }

                // ── Fenced code block ─────────────────────────────────────────
                is MdBlock.FencedCode -> {
                    val annotated = remember(block.content) {
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = EmuTones.container,
                                    color      = EmuTones.onSurface,
                                )
                            ) { append(block.content) }
                        }
                    }
                    Text(
                        text     = annotated,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = EmuTones.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp),
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
                    val annotated = remember(block.text) {
                        parseInline(block.text, EmuTones.onSurface)
                    }
                    Text(
                        text  = annotated,
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
                    - Package com.foo_bar_baz should not be italic

                    ---

                    ## Bug fixes

                    1. Download progress bar now shows correct percentage
                    2. *Shizuku* connection is re-checked on resume, not just on launch

                    > Note: users on Android 10 may need to re-grant Shizuku permission
                    after this update.

                    Plain paragraph with **bold**, *italic*, and `inline code` all in one line.

                    _italic **bold** italic_

                    **bold _italic_ bold**

                    ```
                    val x = "hello"
                    println(x)
                    ```
                """.trimIndent(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}
