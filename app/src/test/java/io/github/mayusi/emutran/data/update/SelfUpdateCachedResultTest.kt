package io.github.mayusi.emutran.data.update

import android.util.Log
import io.github.mayusi.emutran.data.source.HttpCache
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.IntentInstaller
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Unit tests for FIX #15 — the 24h-gated cached path in
 * [SelfUpdateRepository.checkSelfUpdate].
 *
 * When the 24h rate-limit gate is active (last check < 24h ago) the repository
 * short-circuits to [SelfUpdateRepository.cachedSelfUpdateResult] instead of
 * hitting the network. That helper previously read the HttpCache with
 * `Long.MAX_VALUE` as maxAge, so a months-old release blob could be re-served as
 * "Available" forever. The fix bounds the read to [SelfUpdateRepository.SELF_CHECK_INTERVAL_MS]
 * (the same 24h window): a stale entry is rejected by [HttpCache.get] (returns
 * null past the age) and the helper surfaces [SelfUpdateResult.Failed] instead.
 *
 * All collaborators are MockK'd. [Log] is statically stubbed because the JVM
 * unit runtime has no real Android Log. The gate is forced active by stubbing
 * [UpdateStateStore.selfCheckLastEpoch] to "now" so force=false takes the
 * cached path without any network mocking.
 */
class SelfUpdateCachedResultTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val client = mockk<okhttp3.OkHttpClient>(relaxed = true)
    private val httpCache = mockk<HttpCache>(relaxed = true)
    private val downloader = mockk<ApkDownloader>(relaxed = true)
    private val store = mockk<UpdateStateStore>(relaxed = true)
    private val intentInstaller = mockk<IntentInstaller>(relaxed = true)

    private val repo = SelfUpdateRepository(
        context = context,
        client = client,
        httpCache = httpCache,
        downloader = downloader,
        store = store,
        json = Json { ignoreUnknownKeys = true },
        intentInstaller = intentInstaller,
    )

    private val releasesUrl =
        "https://api.github.com/repos/mayusi/EmuTran/releases/latest"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        // Force the 24h gate ACTIVE so checkSelfUpdate(force=false) takes the
        // cached path (last check was "just now").
        every { store.selfCheckLastEpoch } returns flowOf(System.currentTimeMillis())
    }

    @Test
    fun `gated cache read is bounded by the 24h interval, not Long MAX_VALUE`() = runTest {
        val maxAgeSlot = slot<Long>()
        // Return null regardless — we only care about the maxAge argument here.
        coEvery { httpCache.get(eq(releasesUrl), capture(maxAgeSlot)) } returns null

        repo.checkSelfUpdate(force = false)

        coVerify(exactly = 1) { httpCache.get(eq(releasesUrl), any()) }
        assertThat(maxAgeSlot.captured).isEqualTo(SelfUpdateRepository.SELF_CHECK_INTERVAL_MS)
        // Regression guard: must NOT pass an unbounded age.
        assertThat(maxAgeSlot.captured).isNotEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `stale cache entry rejected by max-age - returns Failed not Available`() = runTest {
        // HttpCache.get returns null when the entry is older than the requested
        // maxAge — i.e. the stale months-old blob no longer satisfies the read.
        coEvery { httpCache.get(eq(releasesUrl), any()) } returns null

        val result = repo.checkSelfUpdate(force = false)

        assertThat(result).isInstanceOf(SelfUpdateResult.Failed::class.java)
    }

    @Test
    fun `fresh cache entry within window - parses to a result`() = runTest {
        // A body within the 24h window IS returned by HttpCache.get; the helper
        // parses it. BuildConfig.VERSION_NAME in unit tests is typically the
        // current/dev version, so a tag at-or-below it parses to UpToDate; the
        // important assertion is that it is NOT Failed (the cache was usable).
        val body = """
            {
              "tag_name": "v0.0.1",
              "draft": false,
              "prerelease": false,
              "body": "old release",
              "assets": []
            }
        """.trimIndent()
        coEvery { httpCache.get(eq(releasesUrl), any()) } returns
            HttpCache.Entry(etag = "etag", body = body, savedAtMs = System.currentTimeMillis())

        val result = repo.checkSelfUpdate(force = false)

        assertThat(result).isNotInstanceOf(SelfUpdateResult.Failed::class.java)
    }
}
