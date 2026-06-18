package io.github.mayusi.emutran.data.source

import io.github.mayusi.emutran.data.manifest.AppEntry

/**
 * Resolves an [AppEntry] into a concrete download URL.
 *
 * Different sources need different lookup strategies (GitHub Releases
 * API, Gitea API, HTML scraping), but all answer the same question:
 * "given this entry, what's the URL of the APK to download right now
 * and what version is it?"
 */
interface AppSource {
    suspend fun resolve(entry: AppEntry): ResolveResult
}

sealed interface ResolveResult {
    data class Found(
        val apkUrl: String,
        val filename: String,
        val version: String,
        val sizeBytes: Long? = null,
        /**
         * What kind of file this is. Drives where the install loop
         * sends it — APKs go through PackageInstaller, ZIPs land in
         * Emulation/tools/turnip/ for the user to load into emulators.
         */
        val kind: AssetKind = AssetKind.APK,
        /**
         * FIX 1 (SHA-256 sidecar): URL of a "<apkName>.sha256" sidecar asset
         * published alongside the APK in the same release. Non-null only when the
         * source found such an asset. AppSourceRouter fetches this at download time
         * and passes the extracted hex hash to ApkDownloader.download(expectedSha256).
         *
         * HTML-scrape sources (PPSSPP / RetroArch / Dolphin / DuckStation) do not
         * publish sidecars — they leave this null, so those downloads remain unverified
         * (integrity for them depends on the transport hardening in FIX 3).
         */
        val sha256Url: String? = null,
        /**
         * Source-rot self-healing: non-null when this result came from a fallback
         * tier rather than the normal latest-release path. Callers (dashboard,
         * progress screen) surface a subtle note so the user knows an older or
         * alternate release was used. Null means the normal path succeeded — the
         * common case — so no UI noise is emitted.
         */
        val recoveredVia: RecoveryTier? = null,
    ) : ResolveResult

    data class Failed(val reason: String) : ResolveResult

    /** This source can't handle the entry — caller should pick a different source. */
    data object Unsupported : ResolveResult
}

/**
 * Which fallback tier produced a [ResolveResult.Found] when the primary
 * (latest-release) path failed. Used by the UI to emit a subtle recovery note
 * without treating the result as a failure.
 *
 * Only appears on [ResolveResult.Found.recoveredVia]; the primary path always
 * leaves it null.
 */
enum class RecoveryTier {
    /** Resolved via an older published release of the same source (per_page/limit list walk). */
    OLDER_RELEASE,
}

/** What we got back from a source. */
enum class AssetKind { APK, DRIVER_ZIP }
