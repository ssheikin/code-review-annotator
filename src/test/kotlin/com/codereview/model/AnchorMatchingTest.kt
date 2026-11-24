package com.codereview.model

import org.junit.Assert.*
import org.junit.Test

class AnchorMatchingTest {

    private val lines = listOf("alpha", "beta", "  gamma", "delta", "beta", "  gamma")

    @Test
    fun `matchesAt ignores surrounding whitespace`() {
        assertTrue(AnchorMatching.matchesAt(lines, listOf("beta", "gamma"), 2))
        assertFalse(AnchorMatching.matchesAt(lines, listOf("beta", "gamma"), 3))
    }

    @Test
    fun `matchesAt rejects out of range`() {
        assertFalse(AnchorMatching.matchesAt(lines, listOf("gamma", "x"), 6))
        assertFalse(AnchorMatching.matchesAt(lines, listOf("alpha"), 0))
    }

    @Test
    fun `locate picks the occurrence nearest the old position`() {
        assertEquals(2, AnchorMatching.locate(lines, listOf("beta", "gamma"), 1))
        assertEquals(5, AnchorMatching.locate(lines, listOf("beta", "gamma"), 6))
    }

    @Test
    fun `locate returns null when code is gone`() {
        assertNull(AnchorMatching.locate(lines, listOf("z"), 1))
    }

    @Test
    fun `locate does not move short anchors`() {
        assertNull(AnchorMatching.locate(listOf("x", "}", "y", "}"), listOf("}"), 1))
    }
}
