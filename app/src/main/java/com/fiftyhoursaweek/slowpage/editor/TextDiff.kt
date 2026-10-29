package com.fiftyhoursaweek.slowpage.editor

/**
 * The one replacement that turns [old] into [new]: UTF-16 units `start until end` of [old]
 * replaced by [insert]. A text field reports whole values; the core takes range edits
 * (`Document.editUtf16`), so the limit and the history see each change. The range never
 * splits a surrogate pair, which the core would refuse as a bad range.
 */
data class TextEdit(val start: Int, val end: Int, val insert: String)

fun diff(old: String, new: String): TextEdit? {
    if (old == new) return null
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    if (prefix > 0 && Character.isHighSurrogate(old[prefix - 1])) prefix--

    var suffix = 0
    val maxSuffix = minOf(old.length, new.length) - prefix
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    if (suffix > 0 && Character.isLowSurrogate(old[old.length - suffix])) suffix--

    return TextEdit(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
}
