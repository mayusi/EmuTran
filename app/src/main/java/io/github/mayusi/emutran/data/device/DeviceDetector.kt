package io.github.mayusi.emutran.data.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coarse device profile derived from cheap, always-available signals.
 *
 * GPU vendor inference here is heuristic — for the real GPU string we'd
 * spin up an offscreen EGL context, which is overkill for v1 and adds a
 * frame of init cost. SoC string from Build.HARDWARE is usually enough to
 * pick the right Turnip variant.
 */
@Singleton
class DeviceDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun detect(): DeviceProfile {
        val vendor = vendorFor(Build.MANUFACTURER, Build.MODEL)
        val totalRamMb = totalRamMb()
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            vendor = vendor,
            totalRamMb = totalRamMb,
            isDualScreenGuess = vendor.isDualScreenLikely,
        )
    }

    private fun totalRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return -1
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024L * 1024L)
    }

    private fun vendorFor(manuf: String?, model: String?): HandheldVendor {
        val m = (manuf.orEmpty() + " " + model.orEmpty()).lowercase()
        return when {
            "ayn" in m || "odin" in m -> if ("thor" in m) HandheldVendor.AYN_DUAL else HandheldVendor.AYN
            "retroid" in m -> HandheldVendor.RETROID
            "anbernic" in m -> if ("ds" in m) HandheldVendor.ANBERNIC_DUAL else HandheldVendor.ANBERNIC
            "ayaneo" in m -> if ("ds" in m) HandheldVendor.AYANEO_DUAL else HandheldVendor.AYANEO
            else -> HandheldVendor.GENERIC
        }
    }
}

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val hardware: String,
    val androidVersion: String,
    val sdkInt: Int,
    val vendor: HandheldVendor,
    val totalRamMb: Long,
    val isDualScreenGuess: Boolean,
)

enum class HandheldVendor(val display: String, val isDualScreenLikely: Boolean) {
    AYN("AYN", false),
    AYN_DUAL("AYN (dual screen)", true),
    RETROID("Retroid", false),
    ANBERNIC("Anbernic", false),
    ANBERNIC_DUAL("Anbernic (dual screen)", true),
    AYANEO("AYANEO", false),
    AYANEO_DUAL("AYANEO (dual screen)", true),
    GENERIC("Generic Android", false),
}
