package io.github.mayusi.emutran.domain.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Regression test for the DEBUGGING fix on the SHIZUKU install path
 * (FIX #6, pipe-buffer deadlock).
 *
 * Root cause: [ShizukuInstaller] used to call waitFor() FIRST and only then
 * read stdout/stderr. `pm install` can emit more output than the OS pipe
 * buffer holds; once that buffer fills, the subprocess blocks writing into a
 * full pipe while we block on waitFor() — neither side ever moves and the
 * install hangs forever on large/verbose installs.
 *
 * The fix drains stdout AND stderr CONCURRENTLY with waitFor() on background
 * IO coroutines, then joins. This test reproduces the exact full-pipe scenario
 * with a [PipedInputStream]/[PipedOutputStream] pair whose buffer is much
 * smaller than the payload: the writer can only finish (and the "process" can
 * only "exit") once a reader keeps draining. We can't drive the private,
 * reflection-on-`rikka.shizuku.Shizuku` install() path from a plain JVM test,
 * so we exercise the drain-ordering pattern in isolation — the very pattern the
 * fix introduced.
 *
 * The assertion is "it completes within the timeout with all bytes captured".
 * The OLD ordering (wait, then read) would deadlock here and trip the timeout.
 */
class ShizukuInstallerDrainOrderTest {

    /** Larger than any typical OS pipe buffer (commonly 64 KiB), so a
     *  non-draining reader is guaranteed to wedge the writer. */
    private val payloadSize = 512 * 1024

    @Test
    fun `concurrent drain does not deadlock when output exceeds the pipe buffer`() = runTest {
        // A small fixed-capacity pipe stands in for the OS stdout pipe: the
        // writer thread (our fake subprocess) blocks once it's full, exactly
        // like pm install would.
        val pipeOut = PipedOutputStream()
        val stdout: InputStream = PipedInputStream(pipeOut, 8 * 1024)

        // stderr stays empty in the success case, mirroring a clean install.
        val stderr: InputStream = ByteArrayInputStreamEmpty()

        // The "subprocess": stream the whole payload, then "exit". Because the
        // pipe is tiny, write() only returns once a reader keeps draining — so
        // this thread completing IS the proof that the drain ran concurrently.
        val writer = thread(start = true, name = "fake-pm-install") {
            pipeOut.use { out ->
                val chunk = ByteArray(4 * 1024) { 'A'.code.toByte() }
                var written = 0
                while (written < payloadSize) {
                    out.write(chunk)
                    written += chunk.size
                }
            }
        }

        // waitFor(): the process has "exited" only once the writer thread is done.
        val waitFor: () -> Int = {
            writer.join()
            0
        }

        // Mirror the production drain ordering: start both readers on IO, THEN
        // waitFor(), THEN await the readers. If ordering were reversed this
        // wedges and withTimeout fails the test.
        val (exit, capturedOut, capturedErr) = withTimeout(5_000) {
            coroutineScope {
                val stdoutJob = async(Dispatchers.IO) {
                    stdout.bufferedReader().use { it.readText() }
                }
                val stderrJob = async(Dispatchers.IO) {
                    stderr.bufferedReader().use { it.readText() }
                }
                val code = waitFor()
                Triple(code, stdoutJob.await(), stderrJob.await())
            }
        }

        assert(exit == 0) { "expected clean exit, got $exit" }
        assert(capturedOut.length == payloadSize) {
            "expected to capture all $payloadSize stdout bytes, got ${capturedOut.length}"
        }
        assert(capturedErr.isEmpty()) { "expected empty stderr, got '${capturedErr.take(80)}'" }
    }

    /** Minimal always-empty stream so stderr drains instantly without pulling
     *  in extra test deps. */
    private class ByteArrayInputStreamEmpty : InputStream() {
        override fun read(): Int = -1
    }
}
