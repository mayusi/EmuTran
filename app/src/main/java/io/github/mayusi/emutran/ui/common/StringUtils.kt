package io.github.mayusi.emutran.ui.common

/** Join up to [max] names, appending "+N more" when the list is longer. */
fun truncatedNames(names: List<String>, max: Int = 3): String {
    if (names.size <= max) return names.joinToString(", ")
    val shown = names.take(max).joinToString(", ")
    return "$shown +${names.size - max} more"
}
