package io.github.mayusi.emutran.domain.drivers

import io.github.mayusi.emutran.data.device.GpuFamily
import io.github.mayusi.emutran.data.device.GpuInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, no-I/O provider of a short one-line driver hint for a given
 * emulator on an Adreno GPU.
 *
 * Custom GPU drivers from K11MCH1/AdrenoToolsDrivers come in two broad
 * flavours (see [DriverStager]): the official Qualcomm Vulkan builds
 * (Qualcomm_8xx / 8Elite2) and the open-source Mesa "Turnip" builds.
 * Neither is universally better — it's emulator- and game-dependent —
 * but community consensus leans one way per emulator:
 *
 *   - Heavy Vulkan-translation workloads (Switch, PS2, PS Vita, PS3
 *     emulation) frequently run better on Turnip, which tends to expose
 *     more recent Vulkan features and extensions.
 *   - Everything else (and anything we don't have a strong opinion on)
 *     is steered toward the official Qualcomm driver, which is the more
 *     conservative, broadly-stable default.
 *
 * Hints are intentionally QUALIFIED ("often", "usually") and never
 * version-pinned — they're a nudge, not a guarantee. The real source of
 * truth is the user trying both, which the staged README already
 * explains.
 *
 * This class holds no state and does no I/O, so it's trivially testable.
 * Keyed off [AppEntry.id] (the canonical package-style id from the pack).
 */
@Singleton
class DriverHintProvider @Inject constructor() {

    /**
     * Returns a short one-line driver hint for [appId] when running on an
     * Adreno [gpu], or null when:
     *   - the GPU is not Adreno (custom Turnip/Qualcomm drivers are
     *     Adreno-only — there is nothing to hint), or
     *   - [appId] is not a known emulator we have an opinion about.
     */
    fun hintFor(appId: String, gpu: GpuInfo): String? {
        if (gpu.family != GpuFamily.ADRENO) return null
        return when {
            appId in TURNIP_PREFERRED -> HINT_TURNIP
            appId in QUALCOMM_PREFERRED -> HINT_QUALCOMM
            else -> null
        }
    }

    companion object {
        /** Conservative, qualified hint strings. Single line each. */
        const val HINT_TURNIP = "Turnip drivers are often preferred here"
        const val HINT_QUALCOMM = "Qualcomm driver is usually the most stable"

        /**
         * Emulators where heavy Vulkan translation tends to favour the
         * open-source Mesa "Turnip" builds. IDs are the real Android
         * package ids from the manifest (see ObtainiumPackParser's
         * RECOMMENDED_IDS), not guesses.
         *
         * Switch: Eden. PS2: NetherSX2 (both the stock and Turnip forks).
         * PS3: aPS3e. PS Vita: Vita3K.
         */
        private val TURNIP_PREFERRED = setOf(
            "dev.eden.eden_emulator",   // Eden (Switch)
            "xyz.aethersx2.android",    // NetherSX2 (PS2)
            "xyz.aethersx2.tturnip",    // NetherSX2-Turnip (PS2)
            "aenu.aps3e",               // aPS3e (PS3)
            "org.vita3k.emulator",      // Vita3K (PS Vita)
        )

        /**
         * Emulators steered toward the conservative, broadly-stable
         * official Qualcomm Vulkan driver. Real Android package ids.
         */
        private val QUALCOMM_PREFERRED = setOf(
            "org.dolphinemu.dolphinemu",       // Dolphin (GC/Wii)
            "info.cemu.cemu",                  // Cemu (Wii U)
            "org.azahar_emu.azahar",           // Azahar (3DS)
            "me.magnum.melonds",               // MelonDS (DS)
            "org.ppsspp.ppsspp",               // PPSSPP (PSP)
            "com.github.stenzek.duckstation",  // DuckStation (PS1)
            "com.flycast.emulator",            // Flycast (Dreamcast)
        )
    }
}
