package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class UndoReviewChangeAction : AnAction(
    "Undo Review Change",
    "Undo the last change to review comments",
    AllIcons.Actions.Undo
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewSessionService.getInstance(project).undo()
        syncUi(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ReviewSessionService.getInstance(project).canUndo
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class RedoReviewChangeAction : AnAction(
    "Redo Review Change",
    "Redo the last undone change to review comments",
    AllIcons.Actions.Redo
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewSessionService.getInstance(project).redo()
        syncUi(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ReviewSessionService.getInstance(project).canRedo
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private fun syncUi(project: Project) {
    InlineCommentManager.resyncAll(project)
    ReviewToolWindowFactory.refreshPanel(project)
}
