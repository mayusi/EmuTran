package io.github.mayusi.emutran.domain.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mayusi.emutran.data.source.ApkAssetFilter
import io.github.mayusi.emutran.data.source.HttpsDowngradeInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams an APK from a URL into app-private cache storage with progress.
 *
 * App-private cache is the right home for transient APKs:
 *  - No storage permission needed.
 *  - The FileProvider in AndroidManifest exposes this dir to the installer.
 *  - Android can clean it up under pressure if we forget.
 *
 * Features:
 *  - Retry transient failures (IOException, 5xx, 408, 429) with exponential
 *    backoff: up to 4 attempts, delays ~1s / 2s / 4s between retries.
 *  - HTTP Range-resume: if a partial file exists from a prior failed attempt,
 *    sends `Range: bytes=<existing>-` and appends. If the server returns 200
 *    instead of 206, restarts from scratch (truncates the partial file).
 *    Progress.Chunk.downloaded includes the pre-existing bytes so the UI bar
 *    starts above 0 on a resumed download.
 *  - Optional SHA-256 verification: pass expectedSha256 to enable. If the
 *    digest of the completed file doesn't match, emits Progress.Failed and
 *    deletes the bad file.
 *
 * FIX 2 (path-traversal): [filename] is sanitized via [ApkAssetFilter.sanitizeFilename]
 * before being used as a file path component. Additionally, after building outFile the
 * canonical path is verified to be inside outDir.
 *
 * FIX 3 (HTTPS downgrade): A network interceptor is added to this downloader's private
 * OkHttpClient that rejects any redirect from an https:// origin to an http:// location.
 * This protects GitHub / Gitea APK downloads from silent MITM downgrades.
 * The ppsspp.org exception is intentional — their cleartext is declared in
 * network_security_config.xml and the interceptor only blocks DOWNGRADE (https→http),
 * not requests that originate as http:// in the first place.
 *
 * Note: the interceptor only rejects the Location header in a 3xx response. The
 * underlying OkHttp followSslRedirects(true) on the shared client is separate;
 * this client is built locally with its own builder so we don't mutate the singleton.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /**
     * FIX 3: A private client built from the shared client that adds a
     * network interceptor to detect and reject HTTPS→HTTP redirect downgrades.
     *
     * We derive from the injected client so we inherit its connection pool,
     * timeouts, and any future TLS config — we just add one interceptor.
     *
     * The interceptor is a NETWORK interceptor (sees the wire-level response
     * before OkHttp processes redirect). If it sees a 3xx whose Location
     * header starts with "http://" while the current request URL was "https://",
     * it throws IOException which surfaces as a transient error and the
     * download loop will retry / fail with a meaningful message.
     *
     * Intentional non-block: requests that ORIGINATE on http:// (ppsspp.org)
     * are not blocked — only the https→http DOWNGRADE is blocked. This keeps
     * the ppsspp.org cleartext-permitted download working.
     */
    private val downloadClient: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor(HttpsDowngradeInterceptor())
        .build()

    /**
     * Downloads [url] into the apks/ subdir of cacheDir.
     *
     * Emits per-chunk progress so the UI can show a real bar, with
     * [Progress.Chunk.downloaded] accounting for any pre-existing bytes from
     * a prior partial download (range-resume).
     *
     * @param expectedSha256 Lowercase hex SHA-256 of the expected file. When
     *   non-null, the completed file is hashed and [Progress.Failed] is emitted
     *   (+ file deleted) if it doesn't match. Pass null to skip verification.
     */
    fun download(
        url: String,
        filename: String,
        expectedSha256: String? = null,
    ): Flow<Progress> = flow {
        val outDir = File(context.cacheDir, "apks").apply { mkdirs() }

        // FIX 2 (path-traversal): sanitize filename from the source before
        // constructing a File path. Sources already sanitize on their end, but
        // this is a defence-in-depth layer inside the downloader itself so
        // a future call site that skips source-level sanitization is still safe.
        val safeFilename = ApkAssetFilter.sanitizeFilename(filename)
        val outFile = File(outDir, safeFilename)

        // FIX 2: assert that the resolved canonical path is inside outDir.
        // This catches any remaining traversal attempt (e.g. symlink tricks).
        val outDirCanonical = outDir.canonicalPath
        if (!outFile.canonicalPath.startsWith(outDirCanonical + File.separator) &&
            outFile.canonicalPath != outDirCanonical) {
            emit(Progress.Failed("Path traversal detected in filename: $filename"))
            return@flow
        }

        // Retry loop — up to MAX_ATTEMPTS total (1 initial + 3 retries).
        val maxAttempts = 4
        val baseDelayMs = 1_000L
        var lastFailMessage = "Unknown error"

        for (attempt in 1..maxAttempts) {
            val existingBytes = if (outFile.exists()) outFile.length() else 0L

            val request = if (existingBytes > 0) {
                // Attempt to resume from where we left off.
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$existingBytes-")
                    .build()
            } else {
                Request.Builder().url(url).build()
            }

            // transientError: non-null = transient failure message, retry.
            // Permanent failures (non-retryable 4xx, checksum) call return@flow.
            // Successes also call return@flow.
            // FIX 3: Use downloadClient (with HTTPS-downgrade interceptor) not the shared client.
            val transientError: String? = try {
                downloadClient.newCall(request).execute().use { response ->
                    when {
                        // ---- 206: server supports range, append to partial file ----
                        response.code == 206 -> {
                            val body = response.body
                                ?: return@use "Empty body on 206"
                            val rangeLen = body.contentLength().takeIf { it >= 0L } ?: 0L
                            val total = existingBytes + rangeLen
                            emit(Progress.Started(total))
                            body.byteStream().use { input ->
                                RandomAccessFile(outFile, "rw").use { raf ->
                                    raf.seek(existingBytes)
                                    val buf = ByteArray(64 * 1024)
                                    var downloaded = existingBytes
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        raf.write(buf, 0, n)
                                        downloaded += n
                                        emit(Progress.Chunk(downloaded, total))
                                    }
                                }
                            }
                            verifyAndEmitDone(outFile, expectedSha256)
                            return@flow
                        }

                        // ---- 200 but partial file existed: server ignored Range ----
                        response.code == 200 && existingBytes > 0 -> {
                            outFile.delete()  // restart from scratch
                            val body = response.body
                                ?: return@use "Empty body on 200 (no-range fallback)"
                            val total = body.contentLength()
                            emit(Progress.Started(total))
                            body.byteStream().use { input ->
                                outFile.outputStream().use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    var downloaded = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                        downloaded += n
                                        emit(Progress.Chunk(downloaded, total))
                                    }
                                }
                            }
                            verifyAndEmitDone(outFile, expectedSha256)
                            return@flow
                        }

                        // ---- 2xx fresh download ----
                        response.isSuccessful -> {
                            val body = response.body
                                ?: return@use "Empty response body"
                            val total = body.contentLength()
                            emit(Progress.Started(total))
                            body.byteStream().use { input ->
                                outFile.outputStream().use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    var downloaded = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                        downloaded += n
                                        emit(Progress.Chunk(downloaded, total))
                                    }
                                }
                            }
                            verifyAndEmitDone(outFile, expectedSha256)
                            return@flow
                        }

                        // ---- Non-retryable 4xx (not 408 / 429) ----
                        response.code in 400..499
                                && response.code != 408
                                && response.code != 429 -> {
                            emit(Progress.Failed("HTTP ${response.code} ${response.message}"))
                            return@flow
                        }

                        // ---- Transient: 5xx, 408, 429 ----
                        else -> "HTTP ${response.code} ${response.message}"
                    }
                }
            } catch (e: IOException) {
                // Network-level transient failure (socket reset, timeout, etc.)
                e.message ?: e.javaClass.simpleName
            } catch (t: Throwable) {
                // Non-retryable unexpected exception.
                emit(Progress.Failed(t.message ?: t.javaClass.simpleName))
                return@flow
            }

            // Transient error — back off and retry.
            lastFailMessage = transientError ?: lastFailMessage
            if (attempt < maxAttempts) {
                val delayMs = baseDelayMs * (1L shl (attempt - 1))  // 1s, 2s, 4s
                delay(delayMs)
            }
        }

        // Exhausted all attempts.
        emit(Progress.Failed("Failed after $maxAttempts attempts: $lastFailMessage"))
    }.flowOn(Dispatchers.IO)

    /**
     * Verifies [file] against [expectedSha256] (if non-null), then emits
     * [Progress.Done] or [Progress.Failed]. Must be called from inside the
     * flow { } block.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<Progress>.verifyAndEmitDone(
        file: File,
        expectedSha256: String?,
    ) {
        if (expectedSha256 != null) {
            // Fix 5: catch IOException so a file-read failure emits Failed
            // rather than escaping the flow and crashing the coroutine.
            val actual = try {
                sha256Hex(file)
            } catch (e: IOException) {
                file.delete()
                emit(Progress.Failed("Checksum read failed: ${e.message}"))
                return
            }
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                file.delete()
                emit(Progress.Failed(
                    "Checksum mismatch (expected $expectedSha256, got $actual)"
                ))
                return
            }
        }
        emit(Progress.Done(file))
    }

    /**
     * Computes the lowercase hex SHA-256 digest of [file].
     *
     * Throws are NOT caught here; callers must wrap in try/catch(IOException)
     * or use [verifyAndEmitDone] which does so.
     *
     * Fix 5: [verifyAndEmitDone] now catches IOException from this method
     * and emits [Progress.Failed] rather than letting it escape the flow.
     */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface Progress {
        data class Started(val totalBytes: Long) : Progress
        data class Chunk(val downloaded: Long, val totalBytes: Long) : Progress
        data class Done(val file: File) : Progress
        data class Failed(val message: String) : Progress
    }
}
