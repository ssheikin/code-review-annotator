package com.codereview.actions

import com.codereview.editor.resolveCommentPath
import com.codereview.model.CommentScope
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import javax.swing.Icon

class NextReviewCommentAction : NavigateReviewCommentAction(
    forward = true, "Next Review Comment", "Go to the next line comment", AllIcons.Actions.NextOccurence
)

class PreviousReviewCommentAction : NavigateReviewCommentAction(
    forward = false, "Previous Review Comment", "Go to the previous line comment", AllIcons.Actions.PreviousOccurence
)

abstract class NavigateReviewCommentAction(
    private val forward: Boolean,
    text: String,
    description: String,
    icon: Icon
) : AnAction(text, description, icon) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val comments = lineComments(project)
        if (comments.isEmpty()) return

        val editor = e.getData(CommonDataKeys.EDITOR) ?: FileEditorManager.getInstance(project).selectedTextEditor
        val editorPath = editor?.let { resolveCommentPath(project, it) }
        val target = if (editor == null || editorPath == null) {
            if (forward) comments.first() else comments.last()
        } else {
            val caretLine = editor.caretModel.logicalPosition.line + 1
            val position = compareBy<Pair<String, Int>>({ it.first }, { it.second })
            fun key(c: ReviewComment) = c.filePath!! to c.lineStart!!
            val here = editorPath to caretLine
            if (forward) comments.firstOrNull { position.compare(key(it), here) > 0 } ?: comments.first()
            else comments.lastOrNull { position.compare(key(it), here) < 0 } ?: comments.last()
        }

        val line0 = target.lineStart!! - 1
        // Stay in the current editor when it shows the file, so a diff view is not swapped for a plain editor.
        if (editor != null && editorPath == target.filePath && line0 < editor.document.lineCount) {
            editor.caretModel.moveToLogicalPosition(LogicalPosition(line0, 0))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            return
        }
        val vf = LocalFileSystem.getInstance().findFileByPath("${project.basePath}/${target.filePath}") ?: return
        OpenFileDescriptor(project, vf, line0, 0).navigate(true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && lineComments(project).isNotEmpty()
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun lineComments(project: com.intellij.openapi.project.Project): List<ReviewComment> =
        ReviewSessionService.getInstance(project).currentSession.comments
            .filter { it.scope == CommentScope.LINE && it.filePath != null && it.lineStart != null }
            .sortedWith(compareBy({ it.filePath }, { it.lineStart }))
}
