package io.github.mayusi.emutran.data.storage

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enumerates mounted storage volumes so the UI can offer quick shortcuts
 * ("Internal storage", "SD Card") instead of requiring the user to type a path.
 *
 * Each volume is exposed via [list()], returning primary storage first, then
 * removable volumes (e.g., SD cards). The base path for each volume is suitable
 * for use as a ROM root — callers should append "/Emulation" to match the
 * existing StorageRootStore convention.
 */
@Singleton
class StorageVolumes @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Volume(
        val label: String,
        val path: String,
        val isRemovable: Boolean,
        val isPrimary: Boolean,
    )

    /**
     * Returns all detected storage volumes, sorted by:
     * 1. Primary storage first
     * 2. Then removable volumes (SD cards) in order
     *
     * On API 30+, uses StorageVolume.directory for the absolute path. On older APIs,
     * falls back to reflection on getPath() or uses context.getExternalFilesDirs(null)
     * and walks up to the volume root. Volumes that cannot be resolved are skipped.
     */
    fun list(): List<Volume> {
        val storageManager = context.getSystemService(StorageManager::class.java)
            ?: return emptyList()

        val volumes = mutableListOf<Volume>()

        try {
            for (sv in storageManager.storageVolumes) {
                try {
                    val label = sv.getDescription(context)
                    val isRemovable = sv.isRemovable
                    val isPrimary = sv.isPrimary

                    // Derive the absolute path.
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // API 30+: use StorageVolume.directory
                        sv.directory?.absolutePath
                    } else {
                        // Older API: try reflection on getPath() / getPathFile()
                        getPathViaReflection(sv)
                    } ?: getPathViaExternalFilesDirs(isRemovable, isPrimary)

                    if (path != null) {
                        volumes.add(
                            Volume(
                                label = label,
                                path = path,
                                isRemovable = isRemovable,
                                isPrimary = isPrimary,
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Skip volumes that fail resolution.
                }
            }
        } catch (e: Exception) {
            // If storageVolumes enumeration fails entirely, fall back to a sensible default.
            return listOf(
                Volume(
                    label = "Internal storage",
                    path = context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.absolutePath
                        ?: "/storage/emulated/0",
                    isRemovable = false,
                    isPrimary = true,
                )
            )
        }

        // Sort: primary first, then removable.
        volumes.sortWith(compareBy({ !it.isPrimary }, { !it.isRemovable }))

        return volumes
    }

    /**
     * Attempts to get the absolute path via reflection on getPath() or getPathFile().
     * Returns null if both fail or the reflection is not available.
     */
    private fun getPathViaReflection(sv: Any): String? {
        return try {
            // Try getPathFile() first (available on some API levels).
            val getPathFileMethod = sv.javaClass.getMethod("getPathFile")
            val pathFile = getPathFileMethod.invoke(sv) as? File
            pathFile?.absolutePath
        } catch (e: Exception) {
            try {
                // Fall back to getPath().
                val getPathMethod = sv.javaClass.getMethod("getPath")
                getPathMethod.invoke(sv) as? String
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Fallback: uses context.getExternalFilesDirs(null) to infer volume roots.
     * Walks up from each returned "/storage/.../Android/data/<pkg>/files" to the volume root.
     * Returns the first matching root for the given isPrimary / isRemovable flags.
     */
    private fun getPathViaExternalFilesDirs(isRemovable: Boolean, isPrimary: Boolean): String? {
        val externalDirs = context.getExternalFilesDirs(null)
        for (dir in externalDirs) {
            // Each dir is of the form /storage/.../Android/data/<pkg>/files
            // Walk up 4 levels: files -> data -> <pkg> -> data -> storage root
            var current: File? = dir
            repeat(4) {
                current = current?.parentFile
            }
            if (current != null) {
                // For the primary volume, this is usually /storage/emulated/0.
                // For removable, it's usually /storage/<uuid>.
                // We can't easily distinguish here, so return the path and let
                // the caller decide via isPrimary / isRemovable flags.
                if (isPrimary) {
                    // Prefer the primary volume path (usually first in the list).
                    if (externalDirs.indexOf(dir) == 0) {
                        return current.absolutePath
                    }
                } else {
                    // For removable, take any non-primary path.
                    if (externalDirs.indexOf(dir) > 0) {
                        return current.absolutePath
                    }
                }
            }
        }
        return null
    }
}
