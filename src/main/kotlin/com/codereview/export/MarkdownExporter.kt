package com.codereview.export

import com.codereview.model.CommentScope
import com.codereview.model.ReviewComment
import com.codereview.model.ReviewSession

object MarkdownExporter {

    private const val MAX_SNIPPET_LINES = 15

    fun export(session: ReviewSession, snippetOf: (ReviewComment) -> List<String>? = { null }): String {
        val exportableComments = session.comments.filter { it.scope != CommentScope.REVIEW }
        if (exportableComments.isEmpty() && session.summary.isEmpty()) return ""

        val sb = StringBuilder()
        if (session.summary.isNotEmpty()) {
            sb.appendLine(session.summary)
        } else {
            sb.appendLine("I reviewed your code and have the following comments. Please address them.")
        }
        sb.appendLine()

        if (exportableComments.isNotEmpty()) {
            val sorted = exportableComments.sortedWith(
                compareBy<com.codereview.model.ReviewComment> { it.scope.ordinal }
                    .thenBy { it.filePath ?: "" }
                    .thenBy { it.lineStart ?: Int.MAX_VALUE }
            )

            sorted.forEachIndexed { index, comment ->
                val location = when (comment.scope) {
                    CommentScope.LINE -> {
                        val lineRange = if (comment.lineEnd != null && comment.lineEnd != comment.lineStart) {
                            "${comment.lineStart}-${comment.lineEnd}"
                        } else {
                            "${comment.lineStart}"
                        }
                        "`${comment.filePath}:$lineRange`"
                    }
                    CommentScope.FILE -> "`${comment.filePath}`"
                    CommentScope.REVIEW -> "`Review Comment`"
                }
                val outdatedNote = if (comment.outdated) " (code changed since this comment)" else ""
                sb.appendLine("${index + 1}. **[${comment.type.name}]** $location$outdatedNote - ${comment.text}")
                snippetOf(comment)?.takeIf { it.isNotEmpty() }?.let { appendSnippet(sb, it) }
            }
        }

        return sb.toString().trimEnd() + "\n"
    }

    // Quoting the code keeps the comment meaningful after the agent edits
    // the file and the line numbers no longer point at it.
    private fun appendSnippet(sb: StringBuilder, lines: List<String>) {
        val fence = "   ```"
        sb.appendLine(fence)
        lines.take(MAX_SNIPPET_LINES).forEach { sb.appendLine("   $it".trimEnd()) }
        if (lines.size > MAX_SNIPPET_LINES) sb.appendLine("   …")
        sb.appendLine(fence)
    }
}
