package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.codereview.model.ArchivedReview
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.text.DateFormatUtil

class ShowReviewHistoryAction : AnAction(
    "Review History",
    "Restore a submitted or cleared review",
    AllIcons.Vcs.History
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = ReviewSessionService.getInstance(project)
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(service.reviewHistory.toList())
            .setTitle("Restore Review (Current Comments Are Replaced, Undo Reverts)")
            .setRenderer(SimpleListCellRenderer.create("") { describe(it) })
            .setNamerForFiltering { describe(it) }
            .setItemChosenCallback { entry ->
                service.restoreFromHistory(entry)
                InlineCommentManager.resyncAll(project)
                ReviewToolWindowFactory.refreshPanel(project)
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            ReviewSessionService.getInstance(project).reviewHistory.isNotEmpty()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun describe(entry: ArchivedReview): String {
        val session = entry.session
        val preview = session.summary.lineSequence().firstOrNull { it.isNotBlank() }
            ?: session.comments.firstOrNull()?.text?.lineSequence()?.firstOrNull()
            ?: ""
        return "${DateFormatUtil.formatDateTime(entry.archivedAt)} · ${session.comments.size} comment(s) · ${preview.take(60)}"
    }
}
