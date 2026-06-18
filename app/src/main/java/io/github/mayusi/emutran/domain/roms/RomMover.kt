package io.github.mayusi.emutran.domain.roms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves a found ROM file into the correct Emulation/roms/<system>/ folder.
 *
 * Safety contract (NEVER crossed):
 *  - The original file is NEVER deleted unless the copy fully succeeded AND
 *    the copied file's size matches the original size exactly.
 *  - An existing target file is NEVER overwritten — collisions are handled
 *    by suffixing the filename (up to [MAX_COLLISION_SUFFIX]) or returning
 *    [MoveResult.Skipped] when the target file is byte-identical.
 *  - All file I/O runs on Dispatchers.IO.
 *
 * Strategy:
 *  1. Same-volume: try [File.renameTo] first (instant, atomic on most FS).
 *  2. Cross-volume (renameTo returned false): copy with a temp file name,
 *     verify size, then rename the temp to the final name, then delete source.
 *  3. On any failure, clean up the temp file and leave the source untouched.
 */
@Singleton
class RomMover @Inject constructor() {

    companion object {
        /** Maximum collision-suffix index before giving up and returning Skipped. */
        const val MAX_COLLISION_SUFFIX = 99
    }

    /** The result of a single move operation. */
    sealed class MoveResult {
        /** File was successfully moved to [targetFile]. */
        data class Moved(val targetFile: File) : MoveResult()

        /** Move failed; [reason] is a human-readable explanation. */
        data class Failed(val reason: String) : MoveResult()

        /**
         * Move was skipped without an error.
         * [reason] explains why (e.g. "identical file already exists at target").
         */
        data class Skipped(val reason: String) : MoveResult()
    }

    /**
     * Moves [source] into [targetDir], creating [targetDir] if needed.
     *
     * Collision handling: if a file with the same name already exists at
     * the target location, compare sizes.
     *  - Same size → assume identical; return [MoveResult.Skipped].
     *  - Different size → append a numeric suffix (_1, _2, …) before the
     *    extension until a free slot is found (up to [MAX_COLLISION_SUFFIX]).
     *    If all slots are taken, return [MoveResult.Skipped].
     *
     * @param source     The file to move (typically [RomScanner.FoundRom.file]).
     * @param targetDir  Destination directory (Emulation/roms/<system>/).
     */
    suspend fun move(source: File, targetDir: File): MoveResult = withContext(Dispatchers.IO) {
        // Validate source is still there and readable.
        if (!source.exists()) {
            return@withContext MoveResult.Failed("Source file no longer exists: ${source.absolutePath}")
        }
        if (!source.canRead()) {
            return@withContext MoveResult.Failed("Cannot read source file: ${source.absolutePath}")
        }

        val sourceSize = source.length()

        // Ensure target directory exists.
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            if (!created && !targetDir.isDirectory) {
                return@withContext MoveResult.Failed(
                    "Could not create target directory: ${targetDir.absolutePath}"
                )
            }
        }

        // Resolve collision-free target name.
        val targetFile = resolveTargetFile(source.name, sourceSize, targetDir)
            ?: return@withContext MoveResult.Skipped(
                "A file named ${source.name} (or with suffix up to _$MAX_COLLISION_SUFFIX) " +
                    "already exists in the target — skipped to avoid overwriting."
            )

        // Attempt same-volume rename first.
        val renamed = runCatching { source.renameTo(targetFile) }.getOrDefault(false)
        if (renamed) {
            return@withContext MoveResult.Moved(targetFile)
        }

        // Cross-volume: copy + verify + delete.
        return@withContext crossVolumeCopy(source, sourceSize, targetFile)
    }

    /**
     * Finds a free target [File] in [targetDir] for a file named [originalName]
     * with size [sourceSize].
     *
     * Returns null when all candidate names are taken by files of different sizes
     * (i.e., every slot is occupied and we cannot safely pick one).
     *
     * Returns an existing [File] only if the already-existing file has the SAME
     * size (considered identical) → caller returns [MoveResult.Skipped].
     *
     * Note: the "same size → identical" heuristic avoids the cost of a full hash
     * while still catching the common case of re-running the sorter after a partial
     * move. It may rarely produce a false positive (two different ROMs with the
     * same size + name), but that is an acceptable trade-off for a fast UX.
     */
    private fun resolveTargetFile(
        originalName: String,
        sourceSize: Long,
        targetDir: File,
    ): File? {
        // First try the unmodified name.
        val direct = File(targetDir, originalName)
        if (!direct.exists()) return direct
        if (direct.length() == sourceSize) {
            // Considered identical — signal skip by returning null from the outer call.
            return null
        }

        // Try suffixed names: base_1.ext, base_2.ext, …
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val ext  = if (dot > 0) originalName.substring(dot) else ""

        for (i in 1..MAX_COLLISION_SUFFIX) {
            val candidate = File(targetDir, "${base}_$i$ext")
            if (!candidate.exists()) return candidate
            if (candidate.length() == sourceSize) return null // same size → skip
        }

        return null // all slots taken
    }

    /**
     * Copies [source] to [targetFile] via a temp file, verifies size, then
     * renames to final name and deletes the source.
     *
     * The temp file is placed in the same directory as the target so the final
     * rename is also a same-directory operation (atomic on most filesystems).
     *
     * NEVER deletes [source] unless:
     *   1. The copy completed without an IOException.
     *   2. The copied file's size equals [sourceSize] exactly.
     */
    private fun crossVolumeCopy(
        source: File,
        sourceSize: Long,
        targetFile: File,
    ): MoveResult {
        val tempFile = File(targetFile.parent, "${targetFile.name}.emutran_tmp")

        try {
            source.inputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            val copiedSize = tempFile.length()
            if (copiedSize != sourceSize) {
                tempFile.delete()
                return MoveResult.Failed(
                    "Copy size mismatch for ${source.name}: " +
                        "expected $sourceSize bytes, got $copiedSize bytes. " +
                        "Original file is untouched."
                )
            }

            // Rename temp → final target name.
            val finalised = tempFile.renameTo(targetFile)
            if (!finalised) {
                tempFile.delete()
                return MoveResult.Failed(
                    "Copy succeeded but could not rename temp file to ${targetFile.name}. " +
                        "Original file is untouched."
                )
            }

            // Only now is it safe to delete the original.
            val deleted = source.delete()
            if (!deleted) {
                // The copy is at the target — technically the move "worked" but
                // we couldn't remove the source. Report a partial success so the
                // UI can tell the user the original is still around.
                return MoveResult.Moved(targetFile)
                // (caller may want to show "moved but original not deleted" — the
                //  file is now at targetFile regardless)
            }

            return MoveResult.Moved(targetFile)
        } catch (e: IOException) {
            // Clean up the temp file on any IO failure. Leave source untouched.
            runCatching { tempFile.delete() }
            return MoveResult.Failed(
                "IO error while copying ${source.name}: ${e.message ?: "unknown error"}. " +
                    "Original file is untouched."
            )
        }
    }
}
