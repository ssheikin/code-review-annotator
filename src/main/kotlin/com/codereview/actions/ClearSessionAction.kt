package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ClearSessionAction : AnAction("Clear Session", "Clear all review comments", AllIcons.Actions.GC) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        InlineCommentManager.clearAllInlineComments(project)
        ReviewSessionService.getInstance(project).archiveAndClear()
        ReviewToolWindowFactory.refreshPanel(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
