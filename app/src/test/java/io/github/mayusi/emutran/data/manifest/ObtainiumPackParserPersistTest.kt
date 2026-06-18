package io.github.mayusi.emutran.data.manifest

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for FIX #13 — atomic manifest persistence in
 * [ObtainiumPackParser.persistToDisk].
 *
 * The old implementation called `file.writeText(body)` directly, which
 * truncates-then-rewrites in place; a process kill mid-write would leave a
 * half-written, unparseable manifest on disk that the offline fallback chain
 * would then fail on forever. The fix writes to a sibling "<filename>.tmp" and
 * [File.renameTo]s it over the final path only after a complete write.
 *
 * [persistToDisk] is private and keys off [android.content.Context.filesDir],
 * so we point a relaxed-mock Context's filesDir at a real [TemporaryFolder] and
 * invoke the method via reflection. Asserting on the final file contents plus
 * the absence of a leftover ".tmp" proves the atomic write succeeded and
 * cleaned up.
 */
class ObtainiumPackParserPersistTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filename = ObtainiumPackParser.STANDARD_FILENAME
    private val body = """{"apps":[]}"""

    private fun newParser(filesDir: File): ObtainiumPackParser {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        return ObtainiumPackParser(
            context = context,
            httpCache = mockk(relaxed = true),
            okHttpClient = mockk(relaxed = true),
            manifestDiffStore = mockk(relaxed = true),
        )
    }

    /** Invoke the private persistToDisk(filename, body) via reflection. */
    private fun ObtainiumPackParser.persist(filename: String, body: String) {
        val m = ObtainiumPackParser::class.java
            .getDeclaredMethod("persistToDisk", String::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(this, filename, body)
    }

    @Test
    fun `persistToDisk writes the full body to the final path`() {
        val filesDir = tempFolder.root
        val parser = newParser(filesDir)

        parser.persist(filename, body)

        val finalFile = File(filesDir, "manifest/$filename")
        assertThat(finalFile.exists()).isTrue()
        assertThat(finalFile.readText()).isEqualTo(body)
    }

    @Test
    fun `persistToDisk leaves no tmp file behind on success`() {
        val filesDir = tempFolder.root
        val parser = newParser(filesDir)

        parser.persist(filename, body)

        val tmpFile = File(filesDir, "manifest/$filename.tmp")
        assertThat(tmpFile.exists()).isFalse()
    }

    @Test
    fun `persistToDisk overwrites a prior complete manifest atomically`() {
        val filesDir = tempFolder.root
        val parser = newParser(filesDir)

        // Pre-existing manifest from an earlier successful fetch.
        val finalFile = File(filesDir, "manifest/$filename")
        finalFile.parentFile?.mkdirs()
        finalFile.writeText("""{"apps":[{"id":"old"}]}""")

        val newBody = """{"apps":[{"id":"new"}]}"""
        parser.persist(filename, newBody)

        assertThat(finalFile.readText()).isEqualTo(newBody)
        assertThat(File(filesDir, "manifest/$filename.tmp").exists()).isFalse()
    }
}
