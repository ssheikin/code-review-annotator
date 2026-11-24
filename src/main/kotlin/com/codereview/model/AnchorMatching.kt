package com.codereview.model

import kotlin.math.abs

// Whitespace is ignored so that reformatting does not orphan comments.
object AnchorMatching {

    fun matchesAt(lines: List<String>, anchor: List<String>, start: Int): Boolean {
        val from = start - 1
        if (from < 0 || from + anchor.size > lines.size) return false
        return anchor.indices.all { lines[from + it].trim() == anchor[it].trim() }
    }

    // Short anchors such as "}" or a blank line match all over a file, so moving
    // them would land the comment on unrelated code; they are marked changed instead.
    private const val MIN_DISTINCT_CHARS = 4

    fun locate(lines: List<String>, anchor: List<String>, near: Int): Int? {
        if (anchor.isEmpty() || anchor.size > lines.size) return null
        if (anchor.sumOf { it.trim().length } < MIN_DISTINCT_CHARS) return null
        return (1..lines.size - anchor.size + 1)
            .filter { matchesAt(lines, anchor, it) }
            .minByOrNull { abs(it - near) }
    }
}
