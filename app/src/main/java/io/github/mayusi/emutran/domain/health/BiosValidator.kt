package io.github.mayusi.emutran.domain.health

import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.domain.scaffold.FolderSpec
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// BIOS validation model types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Hash-verification verdict for a single expected BIOS file.
 *
 * - [PRESENT]    — file exists on disk; no known reference hash to compare against,
 *                  so we can only confirm presence, not authenticity.
 * - [OK]         — file exists and its md5 matches a well-documented reference hash.
 * - [WRONG_HASH] — file exists but its md5 does NOT match the known reference hash
 *                  (corrupt, wrong region, or a different dump than expected).
 * - [MISSING]    — file is not present in the system's bios folder.
 */
enum class BiosFileStatus { PRESENT, OK, WRONG_HASH, MISSING }

/**
 * Per-file outcome for one expected BIOS filename.
 *
 * @param filename Expected BIOS filename (e.g. "scph1001.bin").
 * @param status   Presence / hash verdict — see [BiosFileStatus].
 */
data class BiosFileResult(
    val filename: String,
    val status: BiosFileStatus,
)

/**
 * Per-system summary of expected vs. present BIOS files.
 *
 * @param system           BIOS sub-folder name (e.g. "ps1", "dc").
 * @param folderExists      Whether Emulation/bios/<system>/ exists on disk.
 * @param expectedCount     Number of expected BIOS filenames for this system.
 * @param presentCount      How many expected files are actually present (any non-MISSING).
 * @param missingFilenames  Expected filenames not found on disk.
 * @param wrongHashFilenames Present files whose md5 did not match a known reference hash.
 * @param files             Full per-file breakdown in expected order.
 */
data class BiosSystemStatus(
    val system: String,
    val folderExists: Boolean,
    val expectedCount: Int,
    val presentCount: Int,
    val missingFilenames: List<String>,
    val wrongHashFilenames: List<String>,
    val files: List<BiosFileResult>,
)

/**
 * Aggregate result of a full BIOS scan across every system in
 * [FolderSpec.biosFilesBySystem].
 *
 * @param rootConfigured Whether a storage root was available to scan.
 * @param systems        Per-system status, in [FolderSpec.biosFilesBySystem] order.
 *                       Empty when no storage root is configured.
 */
data class BiosValidationResult(
    val rootConfigured: Boolean,
    val systems: List<BiosSystemStatus>,
) {
    /** Total expected files across all systems. */
    val totalExpected: Int get() = systems.sumOf { it.expectedCount }

    /** Total present (non-missing) files across all systems. */
    val totalPresent: Int get() = systems.sumOf { it.presentCount }

    /** Filenames (any system) whose md5 didn't match a known reference hash. */
    val anyWrongHash: Boolean get() = systems.any { it.wrongHashFilenames.isNotEmpty() }
}

// ─────────────────────────────────────────────────────────────────────────────
// BiosValidator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Read-only scanner for the user's BIOS files under Emulation/bios/<system>/.
 *
 * For every system in [FolderSpec.biosFilesBySystem] it reports which expected
 * files are present or missing, and — for the small set of files whose md5 is
 * publicly documented (see [KNOWN_MD5]) — whether the file the USER placed
 * matches the known-good hash.
 *
 * Hash checking is purely a comparison of the user's own files against published
 * reference hashes; EmuTran neither ships nor downloads any BIOS bytes. This is
 * strictly READ-ONLY: it never writes, deletes, or downloads anything.
 *
 * All IO runs on [Dispatchers.IO]. Never throws — a per-file read error degrades
 * gracefully to [BiosFileStatus.PRESENT] (we know it exists, just couldn't hash it).
 */
@Singleton
class BiosValidator @Inject constructor(
    private val storageRoot: StorageRootStore,
) {
    /**
     * Scan all BIOS system folders and return a per-system status report.
     *
     * @param rootPath The storage root to scan. When null, falls back to the
     *   persisted [StorageRootStore.rootPath]; if that is also null, returns a
     *   result with [BiosValidationResult.rootConfigured] = false and no systems.
     */
    suspend fun validate(rootPath: String?): BiosValidationResult = withContext(Dispatchers.IO) {
        val effectiveRoot = rootPath ?: storageRoot.rootPath.first()
            ?: return@withContext BiosValidationResult(rootConfigured = false, systems = emptyList())

        val biosRoot = File(resolveEmulationRoot(effectiveRoot), "bios")

        val systems = FolderSpec.biosFilesBySystem.map { (system, expectedFiles) ->
            scanSystem(biosRoot, system, expectedFiles)
        }

        BiosValidationResult(rootConfigured = true, systems = systems)
    }

    /** Scan a single system folder for its expected BIOS files. */
    private fun scanSystem(
        biosRoot: File,
        system: String,
        expectedFiles: List<String>,
    ): BiosSystemStatus {
        val systemDir = File(biosRoot, system)
        val folderExists = runCatching { systemDir.isDirectory }.getOrDefault(false)

        val files = expectedFiles.map { filename ->
            val file = File(systemDir, filename)
            val exists = folderExists && runCatching { file.isFile }.getOrDefault(false)
            val status = when {
                !exists -> BiosFileStatus.MISSING
                else -> verifyHash(system, filename, file)
            }
            BiosFileResult(filename = filename, status = status)
        }

        val missing = files.filter { it.status == BiosFileStatus.MISSING }.map { it.filename }
        val wrongHash = files.filter { it.status == BiosFileStatus.WRONG_HASH }.map { it.filename }
        val present = files.count { it.status != BiosFileStatus.MISSING }

        return BiosSystemStatus(
            system = system,
            folderExists = folderExists,
            expectedCount = expectedFiles.size,
            presentCount = present,
            missingFilenames = missing,
            wrongHashFilenames = wrongHash,
            files = files,
        )
    }

    /**
     * If [filename] has a known reference md5, hash the user's [file] and compare.
     * Returns [BiosFileStatus.OK] on match, [BiosFileStatus.WRONG_HASH] on mismatch,
     * or [BiosFileStatus.PRESENT] when there's no reference hash (or hashing failed).
     */
    private fun verifyHash(system: String, filename: String, file: File): BiosFileStatus {
        val expected = KNOWN_MD5[hashKey(system, filename)] ?: return BiosFileStatus.PRESENT
        val actual = runCatching { md5(file) }.getOrNull() ?: return BiosFileStatus.PRESENT
        return if (actual.equals(expected, ignoreCase = true)) {
            BiosFileStatus.OK
        } else {
            BiosFileStatus.WRONG_HASH
        }
    }

    /** Compute the lowercase hex md5 of a file, streaming to bound memory use. */
    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashKey(system: String, filename: String): String =
        "$system/${filename.lowercase(Locale.ROOT)}"

    companion object {
        /**
         * Well-documented md5 hashes of common BIOS files, keyed by
         * "<system>/<lowercase-filename>".
         *
         * These are publicly published reference hashes (RetroArch/libretro,
         * Redump, DuckStation docs). They are used ONLY to compare against files
         * the user has independently placed on their own device — no BIOS bytes
         * are bundled or distributed by EmuTran.
         */
        val KNOWN_MD5: Map<String, String> = mapOf(
            // PlayStation 1 (DuckStation / Beetle PSX reference set)
            "ps1/scph5501.bin" to "490f666e1afb15b7362b406ed1cea246",
            "ps1/scph1001.bin" to "924e392ed05558ffdb115408c263dccf",
            "ps1/scph7001.bin" to "1e68c231d0896b7eadcad1d7d8e76129",
            "ps1/scph1002.bin" to "b9d9a0286c33dc6b7237bb13cd46fdee",
            // Dreamcast (Flycast reference)
            "dc/dc_boot.bin" to "e10c53c2f8b90bab96ead2d368858623",
        )
    }
}
