package com.codereview.model

import org.junit.Assert.*
import org.junit.Test

class AnchorMatchingTest {

    private val lines = listOf("a", "b", "  c", "d", "b", "  c")

    @Test
    fun `matchesAt ignores surrounding whitespace`() {
        assertTrue(AnchorMatching.matchesAt(lines, listOf("b", "c"), 2))
        assertFalse(AnchorMatching.matchesAt(lines, listOf("b", "c"), 3))
    }

    @Test
    fun `matchesAt rejects out of range`() {
        assertFalse(AnchorMatching.matchesAt(lines, listOf("c", "x"), 6))
        assertFalse(AnchorMatching.matchesAt(lines, listOf("a"), 0))
    }

    @Test
    fun `locate picks the occurrence nearest the old position`() {
        assertEquals(2, AnchorMatching.locate(lines, listOf("b", "c"), 1))
        assertEquals(5, AnchorMatching.locate(lines, listOf("b", "c"), 6))
    }

    @Test
    fun `locate returns null when code is gone`() {
        assertNull(AnchorMatching.locate(lines, listOf("z"), 1))
    }
}
