package io.github.mayusi.emutran.data.profile

import io.github.mayusi.emutran.data.manifest.ObtainiumPackParser
import io.github.mayusi.emutran.data.selection.SelectedAppsStore
import io.github.mayusi.emutran.data.storage.SetupOptionsStore
import io.github.mayusi.emutran.data.storage.StorageRootStore
import io.github.mayusi.emutran.data.storage.StorageVolumes
import io.github.mayusi.emutran.domain.scaffold.resolveEmulationRoot
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up and restores a small JSON snapshot of the user's setup so they
 * can carry their picks + storage choices to a new device (or recover after
 * a reinstall) without walking the whole setup flow again.
 *
 * What's in the profile:
 *   - storageRoot     — the absolute path the user configured (StorageRootStore)
 *   - pickedIds       — the app ids they selected (SelectedAppsStore)
 *   - isDualScreen    — dual-screen handheld flag (SetupOptionsStore)
 *   - stageGpuDrivers — GPU driver staging opt-in (SetupOptionsStore)
 *
 * This is intentionally a *setup* profile, not a full backup — no ROMs,
 * saves, or BIOS. Those live in the Emulation/ tree and are far too large
 * to embed; the profile just lets the next device rebuild the same choices.
 *
 * == Import validation ==
 *
 * pickedIds from another device might reference apps that no longer exist
 * in the current manifest (renamed/removed forks). [import] validates each
 * id against the UNION of both bundled manifests — standard
 * ([ObtainiumPackParser.loadBundledOnly]) and dual-screen
 * ([ObtainiumPackParser.loadDualScreen]) — so a valid pick is never dropped
 * just because the importing device is the "wrong" screen variant. Unknown
 * ids are dropped and reported in [ImportResult.Success.droppedIds] so the
 * caller can surface a "3 apps no longer available" note.
 *
 * == storageRoot trust boundary ==
 *
 * The imported storageRoot is an *untrusted* string from a file authored on
 * another device. Because the app holds MANAGE_EXTERNAL_STORAGE and later
 * uses this string as a real [File] path, [import] does NOT write it back.
 * Instead it canonicalizes + validates the path against the device's real
 * mounted [StorageVolumes] and returns it as a [ProposedRoot] for the UI to
 * confirm. Only after the user accepts does the caller invoke
 * [applyStorageRoot], which re-validates defensively before persisting.
 *
 * A proposed root is considered valid only when its canonical path is
 * non-blank, absolute, contains no surviving "..", sits underneath one of the
 * real volume roots (path-segment-aware), and does not point inside any app's
 * Android/data or Android/obb sandbox.
 *
 * The safe, device-local fields (validated pickedIds, isDualScreen,
 * stageGpuDrivers) ARE applied immediately by [import]; only the storageRoot
 * is deferred to user confirmation.
 *
 * DataStore ([SelectedAppsStore], [StorageRootStore], [SetupOptionsStore]) dispatches
 * its own I/O internally; no explicit [kotlinx.coroutines.Dispatchers.IO] wrapper is
 * needed here (and adding one would contend with active DataStore collectors).
 * Malformed JSON never throws — it comes back as [ImportResult.Failed].
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val storageRoot: StorageRootStore,
    private val selectedApps: SelectedAppsStore,
    private val setupOptions: SetupOptionsStore,
    private val manifestParser: ObtainiumPackParser,
    private val storageVolumes: StorageVolumes,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // forward-compat: newer schemas shouldn't break old apps
        encodeDefaults = true
    }

    /**
     * Serialize the current setup to JSON and return it alongside the
     * default on-device path the caller may write it to.
     *
     * The caller decides whether to write [ExportResult.json] to
     * [ExportResult.defaultPath] (or somewhere the user picked). This method
     * does not touch the filesystem itself beyond reading the configured
     * root path.
     */
    suspend fun export(): ExportResult {
        val rootPath = storageRoot.rootPath.first() ?: storageRoot.defaultPath
        val picks = selectedApps.pickedIds.first()
        val dualScreen = setupOptions.isDualScreen.first()
        val stageDrivers = setupOptions.stageGpuDrivers.first()

        val profile = SetupProfile(
            schemaVersion = SCHEMA_VERSION,
            storageRoot = rootPath,
            pickedIds = picks.sorted(),   // stable ordering for diff-friendly files
            isDualScreen = dualScreen,
            stageGpuDrivers = stageDrivers,
        )

        val jsonText = json.encodeToString(SetupProfile.serializer(), profile)
        val defaultPath = File(resolveEmulationRoot(rootPath), PROFILE_RELATIVE_PATH).absolutePath

        return ExportResult(path = defaultPath, json = jsonText)
    }

    /**
     * Parse [json] back into a [SetupProfile], validate its app ids against
     * the bundled manifests, apply the safe device-local fields to the stores,
     * and return what was applied / dropped plus a *proposed* (validated, not
     * yet persisted) storage root for the UI to confirm.
     *
     * The imported storageRoot is NOT written here — see [applyStorageRoot].
     *
     * Never throws: malformed or structurally-invalid JSON, or a profile from
     * a newer schema, returns [ImportResult.Failed].
     */
    suspend fun import(json: String): ImportResult {
        val profile = runCatching {
            this@ProfileRepository.json.decodeFromString(SetupProfile.serializer(), json)
        }.getOrElse { e ->
            return ImportResult.Failed(
                reason = e.message ?: "Malformed profile JSON",
            )
        }

        // schemaVersion gate: a file from a newer EmuTran may carry fields or
        // semantics we can't honor. Reject rather than silently mis-apply.
        // Older / equal versions are accepted (current schema is v1).
        if (profile.schemaVersion > SCHEMA_VERSION) {
            return ImportResult.Failed(
                reason = "This profile was made by a newer version of EmuTran",
            )
        }

        // Validate picked ids against the UNION of both bundled variants
        // (standard + dual-screen). Validating against only one variant would
        // silently drop the dual-screen-exclusive emulator ids for dual-screen
        // users (or vice-versa). Unioning means a valid pick is never dropped
        // regardless of which device imports it. Both calls fall back to the
        // bundled asset offline and never throw out of runCatching here.
        val standardIds = runCatching { manifestParser.loadBundledOnly() }
            .getOrDefault(emptyList())
            .map { it.id }
        val dualIds = runCatching { manifestParser.loadDualScreen() }
            .getOrDefault(emptyList())
            .map { it.id }
        val knownIds = (standardIds + dualIds).toSet()

        val (validIds, droppedIds) = profile.pickedIds
            .distinct()
            .partition { it in knownIds }

        // Apply the safe, device-local fields immediately. These cannot escape
        // the app's own data and need no user confirmation.
        selectedApps.save(validIds.toSet())
        setupOptions.setIsDualScreen(profile.isDualScreen)
        setupOptions.setStageGpuDrivers(profile.stageGpuDrivers)

        // The storageRoot is untrusted. Validate + propose it; do NOT persist.
        val currentRoot = storageRoot.rootPath.first() ?: storageRoot.defaultPath
        val proposedRoot = validateProposedRoot(profile.storageRoot, currentRoot)

        return ImportResult.Success(
            appliedCount = validIds.size,
            droppedIds = droppedIds,
            isDualScreen = profile.isDualScreen,
            stageGpuDrivers = profile.stageGpuDrivers,
            proposedStorageRoot = proposedRoot,
        )
    }

    /**
     * Persist a storage root the user explicitly accepted from a prior
     * [import]'s [ImportResult.Success.proposedStorageRoot].
     *
     * Defensively re-validates the path before writing: a [ProposedRoot] could
     * be stale or tampered with between import and confirmation, so this never
     * trusts the earlier verdict. Returns true when the path passed validation
     * and was saved, false when it was rejected (nothing is written).
     *
     * Never throws.
     */
    suspend fun applyStorageRoot(path: String): Boolean {
        val currentRoot = runCatching { storageRoot.rootPath.first() }.getOrNull()
            ?: storageRoot.defaultPath
        val revalidated = validateProposedRoot(path, currentRoot)
        if (revalidated == null || !revalidated.isValid) {
            return false
        }
        return runCatching { storageRoot.save(revalidated.path) }.isSuccess
    }

    /**
     * Canonicalize and validate an untrusted root path against the device's
     * real mounted volumes. Returns a [ProposedRoot] describing the canonical
     * path, whether it is safe to adopt, and whether it differs from
     * [currentRoot] — or null if the input was blank/absent.
     *
     * A path is valid only when, after canonicalization:
     *   - it is non-blank and absolute,
     *   - no ".." segment survives (defends against traversal that
     *     canonicalization didn't fully collapse on a missing path),
     *   - it sits underneath one real volume root (path-segment-aware), and
     *   - it does not point inside any app's Android/data or Android/obb
     *     sandbox.
     */
    private fun validateProposedRoot(raw: String, currentRoot: String): ProposedRoot? {
        if (raw.isBlank()) return null

        // Canonicalize the candidate. canonicalFile collapses ".." and symlinks
        // where the path exists; on a missing path it still normalizes the
        // textual form. Fall back to absoluteFile if canonicalization fails.
        val canonical = runCatching { File(raw).canonicalFile }
            .getOrElse { runCatching { File(raw).absoluteFile }.getOrNull() }
            ?: return ProposedRoot(path = raw, isValid = false, differsFromCurrent = raw != currentRoot)

        val canonicalPath = canonical.path
        val differs = canonicalPath != currentRoot

        val isValid = isCanonicalRootSafe(canonical)
        return ProposedRoot(
            path = canonicalPath,
            isValid = isValid,
            differsFromCurrent = differs,
        )
    }

    /** Core safety predicate over an already-canonicalized [candidate]. */
    private fun isCanonicalRootSafe(candidate: File): Boolean {
        val path = candidate.path
        if (path.isBlank()) return false
        if (!candidate.isAbsolute) return false
        // Any surviving ".." means canonicalization didn't fully resolve the
        // path (e.g. it escaped above a real prefix) — reject defensively.
        if (path.split('/').any { it == ".." }) return false

        // Reject any app sandbox. A storage *root* for emulation content should
        // never live inside Android/data or Android/obb — neither ours nor
        // another app's. Blocking all of them is strictly safer than trying to
        // allow "our own" sandbox (which the profile never legitimately uses).
        val normalizedForSegments = "/" + path.trim('/') + "/"
        if ("/Android/data/" in normalizedForSegments ||
            "/Android/obb/" in normalizedForSegments
        ) {
            return false
        }

        // Must sit under one of the real mounted volume roots.
        val volumeRoots = runCatching { storageVolumes.list() }
            .getOrDefault(emptyList())
            .mapNotNull { vol ->
                runCatching { File(vol.path).canonicalFile.path }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
        if (volumeRoots.isEmpty()) return false

        return volumeRoots.any { root -> isPathUnder(path, root) }
    }

    /**
     * Path-segment-aware "is [child] under [parent]" check. Equal paths count
     * as "under". Guards against the prefix-string trap where
     * "/storage/emulated/0evil" naively startsWith "/storage/emulated/0".
     */
    private fun isPathUnder(child: String, parent: String): Boolean {
        val c = child.trimEnd('/')
        val p = parent.trimEnd('/')
        if (c == p) return true
        return c.startsWith("$p/")
    }

    companion object {
        /** Bump when the on-disk profile shape changes incompatibly. */
        const val SCHEMA_VERSION = 1

        /** Default file location, relative to the resolved Emulation root. */
        const val PROFILE_RELATIVE_PATH = "EmuTran/profile.json"
    }
}

/**
 * Versioned, serializable snapshot of the user's setup choices.
 *
 * [schemaVersion] lets a future app reject or migrate older/newer files
 * instead of silently mis-parsing them.
 */
@Serializable
data class SetupProfile(
    val schemaVersion: Int,
    val storageRoot: String,
    val pickedIds: List<String>,
    val isDualScreen: Boolean,
    val stageGpuDrivers: Boolean,
)

/**
 * Result of [ProfileRepository.export].
 *
 * @property path the default absolute path the caller may write [json] to
 *   (Emulation/EmuTran/profile.json under the configured storage root).
 * @property json the serialized profile JSON, ready to write or share.
 */
data class ExportResult(
    val path: String,
    val json: String,
)

/**
 * A storage root from an imported profile that has been canonicalized and
 * validated but NOT yet persisted. The UI presents this to the user and, only
 * on acceptance, calls [ProfileRepository.applyStorageRoot] with [path].
 *
 * @property path the canonicalized candidate path (what would be saved).
 * @property isValid true when the path passed every safety check and is safe
 *   to adopt; when false the UI should not offer to apply it.
 * @property differsFromCurrent true when [path] differs from what this device
 *   currently has configured — lets the UI skip a needless "change root?"
 *   prompt when nothing actually changed.
 */
data class ProposedRoot(
    val path: String,
    val isValid: Boolean,
    val differsFromCurrent: Boolean,
)

/** Result of [ProfileRepository.import]. */
sealed interface ImportResult {
    /**
     * Import succeeded; the safe device-local fields were written into the
     * stores. The storage root was NOT written — see [proposedStorageRoot].
     *
     * @property appliedCount how many picked ids survived validation and were
     *   saved.
     * @property droppedIds ids present in the file but absent from both bundled
     *   manifests, so they were not applied (UI can warn about these).
     * @property isDualScreen the dual-screen flag that was applied.
     * @property stageGpuDrivers the GPU-driver-staging flag that was applied.
     * @property proposedStorageRoot the validated-but-unsaved storage root for
     *   the UI to confirm, or null when the profile carried no usable root.
     *   Apply it only via [ProfileRepository.applyStorageRoot] after the user
     *   accepts.
     */
    data class Success(
        val appliedCount: Int,
        val droppedIds: List<String>,
        val isDualScreen: Boolean,
        val stageGpuDrivers: Boolean,
        val proposedStorageRoot: ProposedRoot?,
    ) : ImportResult

    /**
     * Import failed before anything was written. The stores are untouched.
     *
     * @property reason human-readable cause (e.g. malformed JSON message, or a
     *   newer-schema rejection).
     */
    data class Failed(
        val reason: String,
    ) : ImportResult
}
