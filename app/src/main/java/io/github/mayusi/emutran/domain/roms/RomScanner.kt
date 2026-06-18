package io.github.mayusi.emutran.domain.roms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks readable directories on the device looking for ROM files the user
 * has already copied over, and returns metadata needed to offer a move.
 *
 * Safety rules (NEVER crossed):
 *  - Never enters or reports files already inside the Emulation/ tree —
 *    those are already sorted. The emulationRoot subtree is entirely skipped.
 *  - Never enters Android sandbox dirs (Android/data, Android/obb, Android/media)
 *    which the app may not have access to under MANAGE_EXTERNAL_STORAGE on some
 *    API levels and which hold no ROM files anyway.
 *  - Never enters hidden dirs (name starts with '.').
 *  - Caps walk depth and total file count to stay fast enough for a foreground scan.
 *  - Catches all IO errors per-directory — one bad symlink never crashes the scan.
 *
 * All file I/O runs on Dispatchers.IO.
 */
@Singleton
class RomScanner @Inject constructor() {

    companion object {
        /** Maximum directory recursion depth from each volume root. */
        const val MAX_DEPTH = 6

        /** Stop scanning after this many ROM candidates have been found (per scan). */
        const val MAX_RESULTS = 2_000

        /** Directories (by exact name) that are never entered, regardless of depth. */
        private val SKIP_DIR_NAMES: Set<String> = setOf(
            "data",     // Android/data — app sandboxes
            "obb",      // Android/obb — expansion files
            "media",    // Android/media — sometimes appears under Android/
            ".Trash",   // macOS / Samba trash dirs
            ".thumbnails",
            "lost+found",
        )
    }

    /**
     * Represents a ROM file found during a scan.
     *
     * @param file         The absolute [File] that was found.
     * @param sizeBytes    Size of the file in bytes.
     * @param classification  What the classifier thinks this file is.
     * @param suggestedTargetDir  The concrete target directory for a
     *   [KnownSystem] classification, or null for Ambiguous/Unknown.
     *   Callers fill this in for Ambiguous after the user picks a system.
     */
    data class FoundRom(
        val file: File,
        val sizeBytes: Long,
        val classification: RomClassifier.Classification,
        val suggestedTargetDir: File?,
    )

    /**
     * Scans [volumeRoots] for ROM files, excluding anything already inside
     * [emulationRoot] (the Emulation/ dir).
     *
     * @param volumeRoots   Roots to search (e.g. /storage/emulated/0,
     *   /storage/<uuid> for SD cards). Obtained via StorageVolumes.list().
     * @param emulationRoot The resolved Emulation/ directory. Everything under
     *   this path is skipped — these files are already in the right place.
     *
     * @return Up to [MAX_RESULTS] [FoundRom] items, sorted: KnownSystem first,
     *   then Ambiguous, then Unknown.
     */
    suspend fun scan(
        volumeRoots: List<File>,
        emulationRoot: File,
    ): List<FoundRom> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FoundRom>()
        val emulationCanonical = runCatching { emulationRoot.canonicalPath }.getOrElse { emulationRoot.absolutePath }

        for (root in volumeRoots) {
            if (results.size >= MAX_RESULTS) break
            walkDir(
                dir            = root,
                depth          = 0,
                emulationRoot  = emulationCanonical,
                results        = results,
                emulationParent = emulationRoot.parentFile,
            )
        }

        // Sort: KnownSystem first (most actionable), then Ambiguous, then Unknown.
        results.sortWith(
            compareBy {
                when (it.classification) {
                    is RomClassifier.Classification.KnownSystem -> 0
                    is RomClassifier.Classification.Ambiguous   -> 1
                    is RomClassifier.Classification.Unknown     -> 2
                }
            }
        )

        results
    }

    /**
     * Recursive directory walker. Catches all IO exceptions per directory so
     * one inaccessible path never aborts the whole scan.
     */
    private fun walkDir(
        dir: File,
        depth: Int,
        emulationRoot: String,
        results: MutableList<FoundRom>,
        emulationParent: File?,
    ) {
        if (depth > MAX_DEPTH) return
        if (results.size >= MAX_RESULTS) return

        // Skip hidden directories (names starting with '.').
        if (dir.name.startsWith('.')) return

        // Skip blocked directory names.
        if (dir.name in SKIP_DIR_NAMES) return

        // Skip the Emulation/ tree itself — those files are already sorted.
        val dirCanonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
        if (dirCanonical == emulationRoot || dirCanonical.startsWith("$emulationRoot/")) return

        // Skip the Android/ directory subtree (sandbox / OBB; no ROMs here).
        if (dir.name.equals("Android", ignoreCase = true) && dir.parentFile == emulationParent?.parentFile) {
            // This is a top-level Android/ directory on a storage volume.
            return
        }
        // More robust: skip any directory whose canonical parent path ends with "/Android"
        // when its own name is "data", "obb", or "media" — already handled by SKIP_DIR_NAMES
        // for the leaf dirs; the "Android" dir itself is also guarded by the check below.
        if (dir.name.equals("Android", ignoreCase = true) && depth <= 1) {
            // Top-level Android/ on any volume root — skip entirely.
            return
        }

        val children = runCatching { dir.listFiles() }.getOrNull() ?: return

        for (child in children) {
            if (results.size >= MAX_RESULTS) break
            if (child.isDirectory) {
                walkDir(child, depth + 1, emulationRoot, results, emulationParent)
            } else if (child.isFile) {
                val classification = RomClassifier.classifyFile(child.name)
                if (classification is RomClassifier.Classification.Unknown) continue

                val sizeBytes = runCatching { child.length() }.getOrDefault(0L)
                val suggestedTargetDir = (classification as? RomClassifier.Classification.KnownSystem)
                    ?.let { known ->
                        // Build Emulation/roms/<system>/ from the emulationRoot string.
                        File(File(emulationRoot, "roms"), known.dir)
                    }

                results.add(
                    FoundRom(
                        file              = child,
                        sizeBytes         = sizeBytes,
                        classification    = classification,
                        suggestedTargetDir = suggestedTargetDir,
                    )
                )
            }
        }
    }
}
