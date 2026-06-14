package io.github.mayusi.emutran.data.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Emulation/ tree under a [File] root using plain java.io.File.
 *
 * This is the post-SAF replacement for SafFolderBuilder. With
 * MANAGE_EXTERNAL_STORAGE we can use ordinary file APIs, which are an
 * order of magnitude faster than DocumentFile (no content-provider round
 * trips) and don't fight the OEM-customized SAF picker.
 *
 * Idempotent: re-running the build on an existing tree is safe — mkdirs
 * skips existing dirs and READMEs are overwritten with current content.
 */
@Singleton
class FileFolderBuilder @Inject constructor() {

    fun build(
        root: File,
        folders: List<String>,
        readmes: Map<String, String>,
    ): Flow<Progress> = flow {
        if (!root.exists() && !root.mkdirs()) {
            emit(Progress.Failed("Could not create root: ${root.absolutePath}"))
            return@flow
        }
        if (!root.canWrite()) {
            emit(Progress.Failed("Root is not writable: ${root.absolutePath}"))
            return@flow
        }

        val total = folders.size + readmes.size
        var done = 0
        emit(Progress.Started(total))

        for (path in folders.sorted()) {
            val dir = File(root, path)
            if (!dir.exists() && !dir.mkdirs()) {
                emit(Progress.Failed("mkdirs failed: ${dir.absolutePath}"))
                return@flow
            }
            if (dir.exists() && !dir.isDirectory) {
                emit(Progress.Failed("Path exists but is a file: ${dir.absolutePath}"))
                return@flow
            }
            done++
            emit(Progress.Step(done, total, "Created $path"))
        }

        for ((relPath, body) in readmes) {
            val file = File(root, relPath)
            file.parentFile?.mkdirs()
            try {
                file.writeText(body, Charsets.UTF_8)
            } catch (t: Throwable) {
                emit(Progress.Failed("Write failed: ${file.absolutePath} — ${t.message}"))
                return@flow
            }
            done++
            emit(Progress.Step(done, total, "Wrote $relPath"))
        }

        emit(Progress.Finished(total))
    }.flowOn(Dispatchers.IO)

    sealed interface Progress {
        data class Started(val total: Int) : Progress
        data class Step(val done: Int, val total: Int, val label: String) : Progress
        data class Finished(val total: Int) : Progress
        data class Failed(val message: String) : Progress
    }
}
