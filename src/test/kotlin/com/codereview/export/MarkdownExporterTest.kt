package com.codereview.export

import com.codereview.model.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownExporterTest {

    @Test
    fun `export empty session returns empty string`() {
        val session = ReviewSession()
        assertEquals("", MarkdownExporter.export(session))
    }

    @Test
    fun `export summary replaces default header`() {
        val session = ReviewSession(summary = "Looks clean")
        val result = MarkdownExporter.export(session)
        assertEquals("Looks clean\n", result)
    }

    @Test
    fun `export file comment`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.FILE, type = CommentType.MUST_DO, text = "Missing tests", filePath = "src/auth.go")
            )
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("1. **[MUST_DO]** `src/auth.go` - Missing tests"))
    }

    @Test
    fun `export line comment single line`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(
                    scope = CommentScope.LINE, type = CommentType.SUGGESTION,
                    text = "Add unit tests", filePath = "src/auth.go",
                    lineStart = 42, lineEnd = null
                )
            )
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("1. **[SUGGESTION]** `src/auth.go:42` - Add unit tests"))
    }

    @Test
    fun `export line comment range`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(
                    scope = CommentScope.LINE, type = CommentType.MUST_DO,
                    text = "Race condition", filePath = "src/handler.go",
                    lineStart = 10, lineEnd = 15
                )
            )
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("1. **[MUST_DO]** `src/handler.go:10-15` - Race condition"))
    }

    @Test
    fun `export sorts by scope then file then line`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, type = CommentType.MUST_DO, text = "Bug", filePath = "z.go", lineStart = 5),
                ReviewComment(scope = CommentScope.FILE, type = CommentType.SUGGESTION, text = "Refactor", filePath = "a.go"),
                ReviewComment(scope = CommentScope.LINE, type = CommentType.QUESTION, text = "Nice", filePath = "a.go", lineStart = 1)
            )
        )
        val result = MarkdownExporter.export(session)
        val lines = result.lines().filter { it.matches(Regex("^\\d+\\..*")) }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("a.go` - Refactor"))  // FILE first
        assertTrue(lines[1].contains("a.go:1"))  // LINE, a.go before z.go
        assertTrue(lines[2].contains("z.go:5"))
    }

    @Test
    fun `export contains header without type legend`() {
        val session = ReviewSession(
            comments = mutableListOf(ReviewComment(scope = CommentScope.FILE, text = "Test", filePath = "a.go"))
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("I reviewed your code and have the following comments."))
        assertFalse(result.contains("Comment types:"))
    }

    @Test
    fun `export quotes snippet under line comment`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, text = "Why?", filePath = "a.go", lineStart = 3)
            )
        )
        val result = MarkdownExporter.export(session) { listOf("x := 1") }
        assertTrue(result.contains("1. **[QUESTION]** `a.go:3` - Why?\n   ```\n   x := 1\n   ```"))
    }

    @Test
    fun `export truncates long snippet`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, text = "Long", filePath = "a.go", lineStart = 1, lineEnd = 40)
            )
        )
        val result = MarkdownExporter.export(session) { (1..40).map { "line $it" } }
        assertTrue(result.contains("   line 15\n   …\n"))
        assertFalse(result.contains("line 16"))
    }

    @Test
    fun `export flags outdated comment`() {
        val session = ReviewSession(
            comments = mutableListOf(
                ReviewComment(scope = CommentScope.LINE, text = "Fix", filePath = "a.go", lineStart = 3, outdated = true)
            )
        )
        val result = MarkdownExporter.export(session)
        assertTrue(result.contains("`a.go:3` (code changed since this comment) - Fix"))
    }
}
