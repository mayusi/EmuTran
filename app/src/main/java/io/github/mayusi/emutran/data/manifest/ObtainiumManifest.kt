package io.github.mayusi.emutran.data.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed model of the Obtainium-Emulation-Pack JSON. We mirror the raw
 * shape on disk for the outer envelope, then crack open the embedded
 * additionalSettings (which is a JSON *string* inside JSON — Obtainium's
 * quirk, not ours) into [AppAdditionalSettings].
 *
 * Only the fields we actually consume are modeled. The full settings
 * blob has 30+ keys most of which are Obtainium-internal; pulling them
 * in adds noise with no benefit.
 */
@Serializable
data class ObtainiumPackJson(
    val apps: List<RawAppEntry> = emptyList(),
)

/**
 * Verbatim shape of one entry in the pack JSON. We keep [additionalSettings]
 * as a raw String here and parse it separately — it really is a JSON
 * blob serialized as a string.
 */
@Serializable
data class RawAppEntry(
    val id: String,
    val url: String,
    val author: String = "",
    val name: String,
    val preferredApkIndex: Int = 0,
    val additionalSettings: String = "{}",
    val categories: List<String> = emptyList(),
    val allowIdChange: Boolean = false,
    val overrideSource: String? = null,
)

/**
 * Subset of Obtainium's per-app settings that we actually act on.
 * Every field defaulted so missing keys in the JSON don't blow up parsing.
 */
@Serializable
data class AppAdditionalSettings(
    val apkFilterRegEx: String = "",
    val invertAPKFilter: Boolean = false,
    val autoApkFilterByArch: Boolean = true,
    val fallbackToOlderReleases: Boolean = false,
    val includePrereleases: Boolean = false,
    val trackOnly: Boolean = false,
    val about: String = "",
    val filterReleaseTitlesByRegEx: String = "",
    val versionExtractionRegEx: String = "",
    val sortMethodChoice: String = "date",
    val allowInsecure: Boolean = false,
)
