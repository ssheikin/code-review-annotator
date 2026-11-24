package com.codereview.mcp

import com.codereview.editor.CommentAnchors
import com.codereview.editor.InlineCommentManager
import com.codereview.export.MarkdownExporter
import com.codereview.export.ProjectSnippets
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class ReviewToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Lists the open review comments the user wrote in the IDE, with their ids, the code they refer to, " +
            "and earlier replies. Address each comment, then call review_resolve or review_reply."
    )
    suspend fun review_list_comments(): String {
        val project = currentCoroutineContext().project
        return withContext(Dispatchers.EDT) { describe(project) }
    }

    @McpTool
    @McpDescription("Marks a review comment as addressed. The reply, if given, tells the reviewer what changed.")
    suspend fun review_resolve(
        @McpDescription("Comment id from review_list_comments") commentId: String,
        @McpDescription("Short note on how the comment was addressed") reply: String = ""
    ): String {
        val project = currentCoroutineContext().project
        return withContext(Dispatchers.EDT) {
            val service = ReviewSessionService.getInstance(project)
            if (reply.isNotBlank()) service.addReply(commentId, AGENT, reply.trim())
            service.setResolved(commentId, true) ?: throw McpExpectedError("No comment with id $commentId")
            syncUi(project)
            "Resolved $commentId."
        }
    }

    @McpTool
    @McpDescription("Replies to a review comment without resolving it, for example to ask a question back.")
    suspend fun review_reply(
        @McpDescription("Comment id from review_list_comments") commentId: String,
        @McpDescription("Reply text") text: String
    ): String {
        val project = currentCoroutineContext().project
        return withContext(Dispatchers.EDT) {
            ReviewSessionService.getInstance(project).addReply(commentId, AGENT, text.trim())
                ?: throw McpExpectedError("No comment with id $commentId")
            syncUi(project)
            "Replied to $commentId."
        }
    }

    @McpTool
    @McpDescription(
        "Blocks until the user changes the review in the IDE (adds, edits, reopens or submits comments), " +
            "then returns the open comments. Call it again after a timeout to keep listening."
    )
    suspend fun review_wait_for_update(
        @McpDescription("Seconds to wait before returning without changes") timeoutSeconds: Int = 240
    ): String {
        val project = currentCoroutineContext().project
        val service = ReviewSessionService.getInstance(project)
        val (startCount, startSession) = withContext(Dispatchers.EDT) { service.changeCount.value to service.currentSession.id }
        val changed = withTimeoutOrNull(timeoutSeconds.coerceIn(1, 3600).seconds) {
            service.changeCount.first { it != startCount }
        }
        if (changed == null) return "No review changes in $timeoutSeconds seconds."
        return withContext(Dispatchers.EDT) {
            val submitted = service.currentSession.id != startSession && service.currentSession.comments.isEmpty()
            val archived = service.reviewHistory.firstOrNull()?.session
            if (submitted && archived != null) {
                "The user submitted the review.\n\n" +
                    MarkdownExporter.export(archived, withIds = true, snippetOf = ProjectSnippets.reader(project))
            } else {
                describe(project)
            }
        }
    }

    private fun describe(project: Project): String {
        CommentAnchors.reanchorAll(project)
        val session = ReviewSessionService.getInstance(project).currentSession
        return MarkdownExporter.export(session, withIds = true, snippetOf = ProjectSnippets.reader(project))
            .ifEmpty { "No open review comments." }
    }

    private fun syncUi(project: Project) {
        InlineCommentManager.resyncAll(project)
        ReviewToolWindowFactory.refreshPanel(project)
    }

    private companion object {
        const val AGENT = "agent"
    }
}
