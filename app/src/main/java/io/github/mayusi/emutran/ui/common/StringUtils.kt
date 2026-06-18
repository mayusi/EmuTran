package io.github.mayusi.emutran.ui.common

/** Join up to [max] names, appending "+N more" when the list is longer. */
fun truncatedNames(names: List<String>, max: Int = 3): String {
    if (names.size <= max) return names.joinToString(", ")
    val shown = names.take(max).joinToString(", ")
    return "$shown +${names.size - max} more"
}

/**
 * Like [truncatedNames] but the "+N more" remainder is computed against a known
 * [total] (which may exceed [names].size when [names] is only a sample). Used by
 * the update notification body, where the total update count and the sample of
 * displayed names are tracked separately.
 *
 * Output format (note the comma before "+", distinct from the list-only
 * overload above):
 *   "Dolphin, PPSSPP, +3 more"     (more than [max] — comma-joined sample + remainder)
 *   "Dolphin, PPSSPP, RetroArch"   (fits within [max])
 */
fun truncatedNames(names: List<String>, total: Int, max: Int = 3): String {
    if (names.size <= max) return names.joinToString(", ")
    val shown = names.take(max).joinToString(", ")
    return "$shown, +${total - max} more"
}
