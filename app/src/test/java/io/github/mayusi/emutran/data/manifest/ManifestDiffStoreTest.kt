package io.github.mayusi.emutran.data.manifest

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Unit tests for [ManifestDiffStore]'s first-run-baseline + diff math.
 *
 * The store itself is DataStore-backed (Context extension delegate), which is
 * not unit-testable on the JVM without Robolectric. The diff DECISION, however,
 * was factored into the pure [ManifestDiffStore.computeDiff] (a behavior-
 * preserving extraction of the body of [ManifestDiffStore.computeAndStoreDiff]).
 * We test that pure function directly, plus the per-variant key derivation that
 * guarantees standard vs dual-screen baselines never cross-contaminate.
 *
 * [Context] and [SetupOptionsStore] are relaxed mocks only so we can construct
 * the store; [computeDiff] never touches them.
 */
class ManifestDiffStoreTest {

    private val context = mockk<Context>(relaxed = true)
    private val setupOptions = mockk<SetupOptionsStore>(relaxed = true)

    // computeDiff (the only thing these tests touch) is pure and never reads json,
    // so a default Json instance is sufficient to satisfy the constructor.
    private fun store() = ManifestDiffStore(context = context, setupOptions = setupOptions, json = Json)

    /** Identity name lookup so added/removed names equal their ids. */
    private val identity: (String) -> String = { it }

    // ── First run records a baseline and emits NO diff ───────────────────────

    @Test
    fun `first run records baseline and emits no diff`() {
        val outcome = store().computeDiff(
            lastSeenRaw = null,
            currentIds = setOf("a", "b", "c"),
            idToName = identity,
        )

        assertThat(outcome).isInstanceOf(DiffOutcome.BaselineOnly::class.java)
        outcome as DiffOutcome.BaselineOnly
        // Baseline is the sorted, newline-joined id set.
        assertThat(outcome.newLastSeen).isEqualTo("a\nb\nc")
    }

    // ── Changed id set emits the correct added/removed ───────────────────────

    @Test
    fun `changed id set emits sorted added and removed names`() {
        val outcome = store().computeDiff(
            lastSeenRaw = "a\nb\nc",
            currentIds = setOf("b", "c", "d", "e"),
            idToName = identity,
            now = 1234L,
        )

        assertThat(outcome).isInstanceOf(DiffOutcome.Changed::class.java)
        outcome as DiffOutcome.Changed
        assertThat(outcome.diff.added).containsExactly("d", "e").inOrder()
        assertThat(outcome.diff.removed).containsExactly("a").inOrder()
        assertThat(outcome.diff.computedAtEpoch).isEqualTo(1234L)
        // Baseline advances to the new id set.
        assertThat(outcome.newLastSeen).isEqualTo("b\nc\nd\ne")
    }

    @Test
    fun `added and removed names are resolved via idToName lookup`() {
        val names = mapOf("id.added" to "Shiny New Emu", "id.gone" to "Old Emu")
        val outcome = store().computeDiff(
            lastSeenRaw = "id.gone\nid.kept",
            currentIds = setOf("id.kept", "id.added"),
            idToName = { names[it] ?: it },
            now = 0L,
        )

        outcome as DiffOutcome.Changed
        assertThat(outcome.diff.added).containsExactly("Shiny New Emu")
        assertThat(outcome.diff.removed).containsExactly("Old Emu")
    }

    // ── No-change refresh emits nothing ──────────────────────────────────────

    @Test
    fun `identical id set emits NoChange`() {
        val outcome = store().computeDiff(
            lastSeenRaw = "a\nb\nc",
            currentIds = setOf("c", "b", "a"), // same set, different order
            idToName = identity,
        )

        assertThat(outcome).isEqualTo(DiffOutcome.NoChange)
    }

    @Test
    fun `empty baseline persisted then empty current is NoChange`() {
        // splitIds("") -> emptySet, so an empty-baseline / empty-current refresh
        // must be NoChange (not a fabricated diff).
        val outcome = store().computeDiff(
            lastSeenRaw = "",
            currentIds = emptySet(),
            idToName = identity,
        )

        assertThat(outcome).isEqualTo(DiffOutcome.NoChange)
    }

    @Test
    fun `only-additions diff has empty removed list`() {
        val outcome = store().computeDiff(
            lastSeenRaw = "a",
            currentIds = setOf("a", "b"),
            idToName = identity,
            now = 0L,
        ) as DiffOutcome.Changed

        assertThat(outcome.diff.added).containsExactly("b")
        assertThat(outcome.diff.removed).isEmpty()
    }

    // ── Per-variant keys never cross-contaminate ─────────────────────────────

    @Test
    fun `per-variant last-seen and pending keys are distinct across variants`() {
        val stdLastSeen = ManifestDiffStore.lastSeenIdsKey(ManifestDiffStore.VARIANT_STANDARD)
        val dualLastSeen = ManifestDiffStore.lastSeenIdsKey(ManifestDiffStore.VARIANT_DUAL_SCREEN)
        val stdPending = ManifestDiffStore.pendingDiffKey(ManifestDiffStore.VARIANT_STANDARD)
        val dualPending = ManifestDiffStore.pendingDiffKey(ManifestDiffStore.VARIANT_DUAL_SCREEN)

        // The four keys must all be different — a standard refresh can never read
        // or overwrite the dual-screen baseline (or its pending diff), and the
        // last-seen vs pending namespaces never collide either.
        assertThat(setOf(stdLastSeen.name, dualLastSeen.name, stdPending.name, dualPending.name))
            .hasSize(4)
    }

    @Test
    fun `variantOf maps the dual-screen flag to the right tag`() {
        assertThat(ManifestDiffStore.variantOf(false)).isEqualTo(ManifestDiffStore.VARIANT_STANDARD)
        assertThat(ManifestDiffStore.variantOf(true)).isEqualTo(ManifestDiffStore.VARIANT_DUAL_SCREEN)
    }

    @Test
    fun `a standard refresh baseline does not match the dual-screen baseline key`() {
        // Concretely model the cross-contamination guard: computing a standard
        // baseline yields a value keyed under the standard key only. We assert
        // the two variant keys differ so the dual-screen baseline is untouched.
        val baseline = store().computeDiff(
            lastSeenRaw = null,
            currentIds = setOf("std.a", "std.b"),
            idToName = identity,
        ) as DiffOutcome.BaselineOnly

        assertThat(baseline.newLastSeen).isEqualTo("std.a\nstd.b")
        assertThat(ManifestDiffStore.lastSeenIdsKey(ManifestDiffStore.VARIANT_STANDARD).name)
            .isNotEqualTo(ManifestDiffStore.lastSeenIdsKey(ManifestDiffStore.VARIANT_DUAL_SCREEN).name)
    }
}
