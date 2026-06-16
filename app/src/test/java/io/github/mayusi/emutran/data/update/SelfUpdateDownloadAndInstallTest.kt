package io.github.mayusi.emutran.data.update

import android.util.Log
import app.cash.turbine.test
import io.github.mayusi.emutran.data.source.HttpCache
import io.github.mayusi.emutran.domain.download.ApkDownloader
import io.github.mayusi.emutran.domain.install.IntentInstaller
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the two DEBUGGING fixes in [SelfUpdateRepository.downloadAndInstall]:
 *
 *  - DEFECT 1 (install-permission gate): when the app lacks the "Install unknown
 *    apps" grant, the flow emits [SelfUpdateProgress.NeedsInstallPermission] BEFORE
 *    downloading anything, instead of pulling the APK and then silently no-op'ing
 *    the system installer.
 *
 *  - DEFECT 2 (resilient sidecar fetch): a single transient non-2xx blip on the
 *    SHA-256 sidecar GET no longer aborts the update — the fetch is retried a few
 *    times. Only when ALL retries genuinely fail does the update abort (MITM
 *    protection preserved).
 *
 * All collaborators are MockK'd — no Android framework or network needed.
 * [android.util.Log] is statically stubbed (the repo logs on the retry / abort
 * paths) because plain JVM unit tests have no real Log implementation.
 *
 * OkHttp's [Response] is mocked rather than built so we can script a
 * fail → fail → succeed sequence for the retry test via [Call.execute] returning
 * different responses on successive calls.
 */
class SelfUpdateDownloadAndInstallTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val client = mockk<OkHttpClient>()
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

    private val apkUrl = "https://example.com/EmuTran-arm64-v0.3.0.apk"
    private val sidecarUrl = "$apkUrl.sha256"
    private val validHash = "a".repeat(64)

    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    /** Builds a mocked [Call] whose [Call.execute] returns [responses] in order. */
    private fun stubCall(vararg responses: Response) {
        val call = mockk<Call>()
        every { call.execute() } returnsMany responses.toList()
        every { client.newCall(any()) } returns call
    }

    private fun response(code: Int, body: String?): Response {
        val resp = mockk<Response>(relaxed = true)
        every { resp.isSuccessful } returns (code in 200..299)
        every { resp.code } returns code
        if (body != null) {
            val rb = mockk<ResponseBody>(relaxed = true)
            every { rb.string() } returns body
            every { resp.body } returns rb
        } else {
            every { resp.body } returns null
        }
        every { resp.close() } returns Unit
        return resp
    }

    // ── DEFECT 1: install-permission gate ─────────────────────────────────────

    @Test
    fun `missing install permission - emits NeedsInstallPermission and never downloads`() = runTest {
        every { intentInstaller.canRequestInstalls() } returns false

        repo.downloadAndInstall(apkUrl, sha256Url = null).test {
            assert(awaitItem() is SelfUpdateProgress.NeedsInstallPermission) {
                "expected NeedsInstallPermission as the first (and only) emission"
            }
            awaitComplete()
        }

        // The APK must NOT be pulled when the OS won't let us install it.
        verify(exactly = 0) { downloader.download(any(), any(), any()) }
    }

    // ── DEFECT 2: resilient sidecar fetch ─────────────────────────────────────

    @Test
    fun `sidecar fails twice then succeeds - update proceeds with the fetched hash`() = runTest {
        every { intentInstaller.canRequestInstalls() } returns true
        // First two GETs return a transient 503; the third returns the real hash.
        stubCall(
            response(503, null),
            response(503, null),
            response(200, validHash),
        )
        // Download emits nothing terminal so the flow simply completes after the
        // sidecar resolves; we only assert that download() was reached with the hash.
        every { downloader.download(any(), any(), any()) } returns flowOf()

        repo.downloadAndInstall(apkUrl, sha256Url = sidecarUrl).test {
            awaitComplete()
        }

        val shaSlot = slot<String?>()
        coVerify(exactly = 1) { downloader.download(eq(apkUrl), any(), captureNullable(shaSlot)) }
        assert(shaSlot.captured == validHash) {
            "transient blips should have been retried; expected hash $validHash, got ${shaSlot.captured}"
        }
        // The sidecar GET was attempted three times (2 failures + 1 success).
        verify(exactly = 3) { client.newCall(any()) }
    }

    @Test
    fun `sidecar fails all attempts - aborts with Failed and never downloads`() = runTest {
        every { intentInstaller.canRequestInstalls() } returns true
        stubCall(
            response(503, null),
            response(503, null),
            response(503, null),
        )

        repo.downloadAndInstall(apkUrl, sha256Url = sidecarUrl).test {
            val item = awaitItem()
            assert(item is SelfUpdateProgress.Failed) {
                "a definitively-unreachable sidecar must abort with Failed, got $item"
            }
            awaitComplete()
        }

        // MITM protection: the APK is never downloaded when integrity can't be verified.
        verify(exactly = 0) { downloader.download(any(), any(), any()) }
        // All configured attempts were exhausted.
        verify(exactly = SelfUpdateRepository.SIDECAR_FETCH_ATTEMPTS) { client.newCall(any()) }
    }
}
