package com.codereview.editor

import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Rectangle

class InlineCommentClickHandler(private val project: Project) : EditorMouseListener {

    override fun mouseClicked(event: EditorMouseEvent) {
        val editor = event.editor
        val point = event.mouseEvent.point

        val inlayList = editor.inlayModel.getBlockElementsInRange(
            0, editor.document.textLength
        )

        for (inlay in inlayList) {
            val renderer = inlay.renderer
            if (renderer !is InlineCommentRenderer) continue

            val bounds = inlay.bounds ?: continue
            if (!bounds.contains(point)) continue

            val comment = renderer.comment

            // Link bounds are stored relative to inlay top (y=0),
            // so offset by bounds.y to get absolute coordinates
            val toggle = renderer.toggleBounds
            if (toggle != null && Rectangle(toggle.x, bounds.y + toggle.y, toggle.width, toggle.height).contains(point)) {
                InlineCommentManager.toggleCollapsed(inlay)
                event.consume()
                return
            }

            val editBounds = renderer.editLinkBounds
            if (editBounds != null) {
                val editRect = Rectangle(
                    editBounds.x, bounds.y + editBounds.y,
                    editBounds.width, editBounds.height
                )
                if (editRect.contains(point)) {
                    editComment(editor, comment)
                    event.consume()
                    return
                }
            }

            val deleteBounds = renderer.deleteLinkBounds
            if (deleteBounds != null) {
                val deleteRect = Rectangle(
                    deleteBounds.x, bounds.y + deleteBounds.y,
                    deleteBounds.width, deleteBounds.height
                )
                if (deleteRect.contains(point)) {
                    deleteComment(comment)
                    event.consume()
                    return
                }
            }

            // Click on inlay body — show popup menu
            showPopupMenu(event, comment)
            event.consume()
            return
        }
    }

    private fun showPopupMenu(event: EditorMouseEvent, comment: ReviewComment) {
        val resolveItem = if (comment.resolved) "Reopen Comment" else "Resolve Comment"
        val items = listOf("Edit Comment", "Reply...", resolveItem, "Delete Comment")
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("[${comment.type.name}] ${comment.text.take(40)}")
            .setItemChosenCallback { chosen ->
                when (chosen) {
                    "Edit Comment" -> editComment(event.editor, comment)
                    "Delete Comment" -> deleteComment(comment)
                    "Reply..." -> replyTo(event.editor, comment)
                    resolveItem -> {
                        val updated = ReviewSessionService.getInstance(project).setResolved(comment.id, !comment.resolved)
                        if (updated != null) InlineCommentManager.updateInlineComment(project, event.editor, updated)
                        ReviewToolWindowFactory.refreshPanel(project)
                    }
                }
            }
            .createPopup()
            .showInBestPositionFor(event.editor)
    }

    private fun editComment(editor: com.intellij.openapi.editor.Editor, comment: ReviewComment) {
        // Hide the display inlay while editing
        InlineCommentManager.removeInlineComment(comment.id)

        val anchorLine = comment.lineEnd ?: comment.lineStart ?: return
        InlineCommentManager.showEditorPopup(
            editor = editor,
            line1Based = anchorLine,
            existingComment = comment,
            onSave = { type, text ->
                val updated = ReviewSessionService.getInstance(project).updateComment(comment.id) {
                    it.copy(type = type, text = text)
                }
                if (updated != null) {
                    InlineCommentManager.addInlineComment(project, editor, updated)
                }
                ReviewToolWindowFactory.refreshPanel(project)
            },
            onCancel = {
                InlineCommentManager.addInlineComment(project, editor, comment)
            }
        )
    }

    private fun replyTo(editor: com.intellij.openapi.editor.Editor, comment: ReviewComment) {
        val text = Messages.showMultilineInputDialog(project, comment.text, "Reply to Comment", "", null, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val updated = ReviewSessionService.getInstance(project).addReply(comment.id, "you", text) ?: return
        InlineCommentManager.updateInlineComment(project, editor, updated)
        ReviewToolWindowFactory.refreshPanel(project)
    }

    private fun deleteComment(comment: ReviewComment) {
        InlineCommentManager.removeInlineComment(comment.id)
        ReviewSessionService.getInstance(project).removeComment(comment.id)
        ReviewToolWindowFactory.refreshPanel(project)
    }
}
