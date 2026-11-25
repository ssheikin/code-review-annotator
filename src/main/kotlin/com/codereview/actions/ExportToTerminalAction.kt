package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.codereview.editor.CommentAnchors
import com.codereview.export.MarkdownExporter
import com.codereview.export.ProjectSnippets
import com.codereview.service.ReviewSessionService
import com.codereview.settings.CodeReviewSettings
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.JBTerminalWidget
import com.intellij.terminal.icons.TerminalIcons
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import javax.swing.Icon

open class ExportToTerminalAction(
    text: String = "Submit Review",
    description: String = "Submit all review comments to the terminal",
    private val useSubmitCommand: Boolean = true,
    icon: Icon = TerminalIcons.OpenTerminal_13x13,
) : AnAction(text, description, icon) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = ReviewSessionService.getInstance(project).currentSession

        if (session.comments.isEmpty() && session.summary.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeReviewAnnotator")
                .createNotification("No review content to submit.", NotificationType.WARNING)
                .notify(project)
            return
        }

        val commentCount = session.comments.count { it.scope != com.codereview.model.CommentScope.REVIEW && !it.resolved }
        CommentAnchors.reanchorAll(project)
        val markdown = MarkdownExporter.export(session, snippetOf = ProjectSnippets.reader(project))

        val command = CodeReviewSettings.getInstance().state.submitCommand.trim()
        if (useSubmitCommand && command.isNotEmpty()) {
            // A file avoids escaping multi-line markdown for the shell.
            val reviewFile = FileUtil.createTempFile("code-review-", ".md", true)
            reviewFile.writeText(markdown)
            TerminalToolWindowManager.getInstance(project)
                .createShellWidget(project.basePath, "Code Review", true, true)
                .sendCommandToExecute(command.replace("{file}", shellQuote(reviewFile.path)))
            finishSubmit(project, commentCount)
            return
        }

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)
        val selectedContent = toolWindow?.contentManager?.selectedContent
        val allContents = toolWindow?.contentManager?.contents

        // Reworked terminal (GoLand 2024.1+): use TerminalWidget.sendCommandToExecute
        val newStyleWidget = selectedContent?.let { TerminalToolWindowManager.findWidgetByContent(it) }
            ?: allContents?.firstNotNullOfOrNull { TerminalToolWindowManager.findWidgetByContent(it) }

        // Classic JediTerm terminal (GoLand < 2024.1): find JBTerminalWidget in component tree
        val oldStyleWidget = if (newStyleWidget == null) {
            selectedContent?.let { UIUtil.findComponentOfType(it.component, JBTerminalWidget::class.java) }
                ?: allContents?.firstNotNullOfOrNull { UIUtil.findComponentOfType(it.component, JBTerminalWidget::class.java) }
        } else null

        if (newStyleWidget == null && oldStyleWidget?.ttyConnector == null) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeReviewAnnotator")
                .createNotification(
                    "No active terminal session found. Open a terminal tab first.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        if (newStyleWidget != null) {
            newStyleWidget.sendCommandToExecute(markdown)
        } else {
            // Bracketed paste: treated as a single block, not executed line-by-line
            oldStyleWidget!!.ttyConnector!!.write("\u001B[200~$markdown\u001B[201~")
        }

        toolWindow?.activate(null)
        finishSubmit(project, commentCount)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun finishSubmit(project: Project, commentCount: Int) {
        InlineCommentManager.clearAllInlineComments(project)
        ReviewSessionService.getInstance(project).submitAndClear()
        ReviewToolWindowFactory.refreshPanel(project)

        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeReviewAnnotator")
            .createNotification(
                "Submitted review with $commentCount comment(s) to terminal.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

// Lets a review reach the agent already running in a terminal tab even when a submit command
// is set for starting new sessions.
class ExportToActiveTerminalAction : ExportToTerminalAction(
    "Submit Review to Active Terminal",
    "Paste all review comments into the active terminal tab",
    useSubmitCommand = false,
    // The two buttons sit side by side, so the same icon would leave them indistinguishable.
    icon = AllIcons.Debugger.Console,
) {
    // Without a submit command, Submit Review already pastes into the active tab.
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (CodeReviewSettings.getInstance().state.submitCommand.isBlank()) {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
