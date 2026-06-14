package io.github.mayusi.emutran.data.device

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries OpenGL for the GL_RENDERER string to identify the GPU vendor
 * and model. On Qualcomm-based Android devices the renderer string is
 * something like "Adreno (TM) 740" — we extract the model number.
 *
 * Why this and not Build.HARDWARE: HARDWARE only tells you the SoC
 * codename ("sun" for SD8 Gen 2) which doesn't uniquely identify the
 * GPU, especially across binned variants. Querying GL itself is the
 * source of truth.
 *
 * Cost is one transient EGL context creation (~tens of ms), done once
 * lazily and cached. The EGL/GL calls are blocking and must NOT run on
 * the main thread — [snapshot] is therefore suspend and always dispatches
 * the actual work on [Dispatchers.IO].
 */
@Singleton
class GpuDetector @Inject constructor() {

    @Volatile private var cached: GpuInfo? = null

    /**
     * Single-thread dispatcher for EGL / GLES work.
     *
     * EGL contexts are bound to a specific OS thread. If [detect] were
     * dispatched on the general Dispatchers.IO pool (which is
     * multi-threaded), two concurrent callers could end up running on
     * different IO threads simultaneously. The second call's
     * eglMakeCurrent would then steal the EGL context from the first
     * thread mid-call, causing glGetString to return null on the first
     * thread and reporting the GPU as UNKNOWN — silently skipping driver
     * staging for Adreno devices.
     *
     * limitedParallelism(1) carves out a single-thread lane from the IO
     * pool. All EGL/GL work runs sequentially on one thread, keeping the
     * context stable.
     */
    private val glDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Guards the detect-or-return-cached critical section. */
    private val detectMutex = Mutex()

    /**
     * Returns a [GpuInfo] snapshot. The first call creates a transient
     * EGL context, runs GL queries, and caches the result; subsequent
     * calls return the cached value immediately. Safe to call from any
     * coroutine context — the blocking work is serialized on a single
     * dedicated IO thread to keep the EGL context on one OS thread.
     */
    suspend fun snapshot(): GpuInfo {
        // Fast path: cache already populated (no lock needed — @Volatile
        // guarantees visibility of the write done under the mutex below).
        cached?.let { return it }
        return detectMutex.withLock {
            // Double-check inside the lock: another coroutine may have
            // completed detection while we were waiting to acquire it.
            cached?.let { return@withLock it }
            val info = withContext(glDispatcher) {
                runCatching { detect() }.getOrDefault(GpuInfo.UNKNOWN)
            }
            cached = info
            info
        }
    }

    private fun detect(): GpuInfo {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return GpuInfo.UNKNOWN

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return GpuInfo.UNKNOWN

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            EGL14.eglTerminate(display)
            return GpuInfo.UNKNOWN
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE,
        )
        val context: EGLContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            return GpuInfo.UNKNOWN
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE,
        )
        val surface: EGLSurface = EGL14.eglCreatePbufferSurface(
            display, configs[0], surfaceAttribs, 0
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return GpuInfo.UNKNOWN
        }

        try {
            EGL14.eglMakeCurrent(display, surface, surface, context)
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            return GpuInfo(
                vendor = vendor,
                renderer = renderer,
                glVersion = glVersion,
                family = classify(vendor, renderer),
                adrenoModel = extractAdrenoModel(renderer),
            )
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun classify(vendor: String, renderer: String): GpuFamily {
        val v = vendor.lowercase()
        val r = renderer.lowercase()
        return when {
            "qualcomm" in v || "adreno" in r -> GpuFamily.ADRENO
            "arm" in v && "mali" in r -> GpuFamily.MALI
            "imagination" in v || "powervr" in r -> GpuFamily.POWERVR
            "nvidia" in v -> GpuFamily.TEGRA
            else -> GpuFamily.UNKNOWN
        }
    }

    /** "Adreno (TM) 740" → 740. */
    private fun extractAdrenoModel(renderer: String): Int? {
        val match = Regex("""adreno\s*(?:\(tm\))?\s*(\d{3,4})""", RegexOption.IGNORE_CASE)
            .find(renderer)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}

data class GpuInfo(
    val vendor: String,
    val renderer: String,
    val glVersion: String,
    val family: GpuFamily,
    /** Adreno model number when [family] == ADRENO, else null. */
    val adrenoModel: Int?,
) {
    companion object {
        val UNKNOWN = GpuInfo("", "", "", GpuFamily.UNKNOWN, null)
    }
}

enum class GpuFamily { ADRENO, MALI, POWERVR, TEGRA, UNKNOWN }
