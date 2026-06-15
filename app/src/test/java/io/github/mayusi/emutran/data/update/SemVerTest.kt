package io.github.mayusi.emutran.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [SemVer] — the minimal semantic-version parser used by
 * [SelfUpdateRepository] to decide whether a GitHub release is newer than
 * the installed build.
 *
 * [SemVer] was bumped from private to internal to enable this test.
 */
class SemVerTest {

    // ── parse ─────────────────────────────────────────────────────────────────

    @Test
    fun `parse standard vMajor dot minor dot patch`() {
        val v = SemVer.parse("v0.3.0")
        assertThat(v.major).isEqualTo(0)
        assertThat(v.minor).isEqualTo(3)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse strips leading v`() {
        val v = SemVer.parse("v1.14.0")
        assertThat(v.major).isEqualTo(1)
        assertThat(v.minor).isEqualTo(14)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse strips leading V uppercase`() {
        val v = SemVer.parse("V2.0.1")
        assertThat(v.major).isEqualTo(2)
        assertThat(v.minor).isEqualTo(0)
        assertThat(v.patch).isEqualTo(1)
    }

    @Test
    fun `parse without v prefix`() {
        val v = SemVer.parse("0.9.0")
        assertThat(v.major).isEqualTo(0)
        assertThat(v.minor).isEqualTo(9)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse two-part version - patch defaults to 0`() {
        val v = SemVer.parse("1.0")
        assertThat(v.major).isEqualTo(1)
        assertThat(v.minor).isEqualTo(0)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse single-part version - minor and patch default to 0`() {
        val v = SemVer.parse("3")
        assertThat(v.major).isEqualTo(3)
        assertThat(v.minor).isEqualTo(0)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse malformed tag - all components default to 0`() {
        val v = SemVer.parse("not-a-version")
        assertThat(v.major).isEqualTo(0)
        assertThat(v.minor).isEqualTo(0)
        assertThat(v.patch).isEqualTo(0)
    }

    @Test
    fun `parse empty string - all components default to 0`() {
        val v = SemVer.parse("")
        assertThat(v.major).isEqualTo(0)
        assertThat(v.minor).isEqualTo(0)
        assertThat(v.patch).isEqualTo(0)
    }

    // ── compareTo (numeric comparison) ────────────────────────────────────────

    @Test
    fun `v0 dot 10 dot 0 is greater than v0 dot 9 dot 0 numeric not lexical`() {
        // Lexical ordering would say "9" > "10" — must be numeric.
        val newer = SemVer.parse("0.10.0")
        val older = SemVer.parse("0.9.0")
        assertThat(newer).isGreaterThan(older)
    }

    @Test
    fun `v1 dot 0 dot 0 is greater than v0 dot 9 dot 9`() {
        assertThat(SemVer.parse("1.0.0")).isGreaterThan(SemVer.parse("0.9.9"))
    }

    @Test
    fun `patch version comparison - 0 dot 2 dot 1 greater than 0 dot 2 dot 0`() {
        assertThat(SemVer.parse("0.2.1")).isGreaterThan(SemVer.parse("0.2.0"))
    }

    @Test
    fun `equal versions compare as equal`() {
        val a = SemVer.parse("1.14.0")
        val b = SemVer.parse("1.14.0")
        assertThat(a.compareTo(b)).isEqualTo(0)
    }

    @Test
    fun `v1 dot 0 equals v1 dot 0 dot 0 - missing patch treated as 0`() {
        val twoPartVersion = SemVer.parse("1.0")
        val threePartVersion = SemVer.parse("1.0.0")
        assertThat(twoPartVersion.compareTo(threePartVersion)).isEqualTo(0)
    }

    @Test
    fun `v-prefix stripped so v1 dot 14 dot 0 equals 1 dot 14 dot 0`() {
        val withPrefix    = SemVer.parse("v1.14.0")
        val withoutPrefix = SemVer.parse("1.14.0")
        assertThat(withPrefix.compareTo(withoutPrefix)).isEqualTo(0)
    }

    @Test
    fun `minor version bump detected correctly`() {
        val next = SemVer.parse("0.3.0")
        val cur  = SemVer.parse("0.2.0")
        assertThat(next).isGreaterThan(cur)
    }

    @Test
    fun `large minor version numbers compared numerically`() {
        // v0.100.0 > v0.99.0 (lexical would fail here too: "100" > "99" happens to be correct
        // but "0.100.0" vs "0.9.0" lexical fails: "9" > "1")
        assertThat(SemVer.parse("0.100.0")).isGreaterThan(SemVer.parse("0.9.0"))
    }
}
