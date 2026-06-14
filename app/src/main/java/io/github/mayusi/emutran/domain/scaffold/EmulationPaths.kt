package io.github.mayusi.emutran.domain.scaffold

import java.io.File

/**
 * Shared path helpers for the Emulation folder tree.
 *
 * Background: the user may have configured their storage root as either:
 *   - a PARENT directory, e.g. /sdcard   → Emulation lives at /sdcard/Emulation/
 *   - the Emulation directory itself, e.g. /sdcard/Emulation (legacy SAF pick)
 *
 * Without this guard, naively appending "Emulation" to the stored root when it
 * already ends in "/Emulation" creates a double-suffix path:
 *   /sdcard/Emulation/Emulation/tools/turnip  ← wrong
 *
 * [resolveEmulationRoot] and [resolveTurnipDir] handle both cases so callers
 * don't have to repeat the check.
 *
 * Note: ProgressViewModel contains the same logic inline (see resolveTurnipDir
 * there). Both could be unified to call these helpers in a future cleanup,
 * but ProgressViewModel is intentionally left untouched here to avoid
 * colliding with parallel agent work.
 */

/**
 * Returns the canonical Emulation root directory given a user-configured
 * [rootPath].
 *
 * - If [rootPath] already ends in a directory named "Emulation" (case-insensitive),
 *   it is returned as-is.
 * - Otherwise "Emulation" is appended as a sub-directory.
 */
fun resolveEmulationRoot(rootPath: String): File {
    val rootFile = File(rootPath)
    return if (rootFile.name.equals("Emulation", ignoreCase = true)) {
        rootFile
    } else {
        File(rootFile, "Emulation")
    }
}

/**
 * Returns the Turnip driver staging directory:
 *   <emulationRoot>/tools/turnip
 *
 * Uses [resolveEmulationRoot] to avoid the double-suffix bug.
 * The caller is responsible for calling [File.mkdirs] if the directory
 * does not yet exist.
 */
fun resolveTurnipDir(rootPath: String): File =
    File(resolveEmulationRoot(rootPath), "tools/turnip")
