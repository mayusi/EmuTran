package io.github.mayusi.emutran.data.update

/**
 * Minimal semantic-version parser shared across the update layer.
 *
 * Strips a leading 'v'/'V', then reads the leading numeric major.minor.patch
 * run and ignores any trailing junk. This makes it lenient to the arch/build
 * suffixes the emulator sources emit in tag names, e.g.:
 *
 *   "1.19.1-arm64-v8a"  → 1.19.1   (RetroArch buildbot arch suffix)
 *   "5.0-21449"         → 5.0.0    (Dolphin "<version>-<rev>" tag)
 *   "1.0-vanilla"       → 1.0.0
 *   "v2120"             → 2120.0.0
 *
 * Non-numeric or missing components default to 0. A string that begins with no
 * digit at all (e.g. "unknown", a "g3ab12c"-style hash) has no leading numeric
 * run — [parse] yields 0.0.0 and [parseOrNull] returns null so callers can
 * distinguish "genuinely 0.0.0" from "unparseable".
 *
 * Note: this parser does NOT attempt to recognise calendar-version tags
 * ("2025.01.15", "2024-12-01"); those start with digits so they parse to a
 * numeric SemVer. Callers that must NOT treat date tags as comparable versions
 * (e.g. the conservative emulator update check) gate on a date heuristic of
 * their own before parsing.
 *
 * Used by [SelfUpdateRepository] (self-update check) and [UpdateRepository]
 * (emulator update check). Kept `internal` so both repositories in this package
 * can share one implementation.
 */
internal data class SemVer(val major: Int, val minor: Int, val patch: Int) :
    Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        /**
         * Leading numeric component run, e.g. "1.19.1" out of "1.19.1-arm64-v8a"
         * or "5.0" out of "5.0-21449". Captures one-to-three dot-separated digit
         * groups; anything after (suffixes, build metadata) is ignored.
         */
        private val LEADING_NUMERIC = Regex("""^(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        /**
         * Lenient parse: never returns null. Strips a leading v/V, extracts the
         * leading numeric run, and defaults missing/non-numeric components to 0.
         */
        fun parse(raw: String): SemVer {
            val clean = raw.trimStart('v', 'V')
            val match = LEADING_NUMERIC.find(clean)
                ?: return SemVer(0, 0, 0)
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: 0,
                minor = match.groupValues[2].toIntOrNull() ?: 0,
                patch = match.groupValues[3].toIntOrNull() ?: 0,
            )
        }

        /**
         * Strict-ish parse for the conservative update check: returns null when
         * the string has no leading numeric version run (e.g. "unknown", a bare
         * git hash, an empty string). Callers treat null as "indeterminate" and
         * decline to show an update badge.
         */
        fun parseOrNull(raw: String): SemVer? {
            val clean = raw.trimStart('v', 'V').trim()
            if (clean.isEmpty()) return null
            val match = LEADING_NUMERIC.find(clean) ?: return null
            return SemVer(
                major = match.groupValues[1].toIntOrNull() ?: 0,
                minor = match.groupValues[2].toIntOrNull() ?: 0,
                patch = match.groupValues[3].toIntOrNull() ?: 0,
            )
        }
    }
}
