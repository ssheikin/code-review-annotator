package com.codereview.model

import kotlin.math.abs

// Whitespace is ignored so that reformatting does not orphan comments.
object AnchorMatching {

    fun matchesAt(lines: List<String>, anchor: List<String>, start: Int): Boolean {
        val from = start - 1
        if (from < 0 || from + anchor.size > lines.size) return false
        return anchor.indices.all { lines[from + it].trim() == anchor[it].trim() }
    }

    fun locate(lines: List<String>, anchor: List<String>, near: Int): Int? {
        if (anchor.isEmpty() || anchor.size > lines.size) return null
        return (1..lines.size - anchor.size + 1)
            .filter { matchesAt(lines, anchor, it) }
            .minByOrNull { abs(it - near) }
    }
}
