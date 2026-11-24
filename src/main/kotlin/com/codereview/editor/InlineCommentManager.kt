package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.Dimension

object InlineCommentManager {

    private val log = Logger.getInstance(InlineCommentManager::class.java)
    private val inlays = mutableMapOf<String, Inlay<InlineCommentRenderer>>()
    // Ids whose collapse state differs from the default: resolved comments start collapsed.
    private val collapsedIds = mutableSetOf<String>()

    fun isCollapsed(comment: ReviewComment): Boolean = (comment.id in collapsedIds) != comment.resolved

    fun toggleCollapsed(inlay: Inlay<*>) {
        val renderer = inlay.renderer as? InlineCommentRenderer ?: return
        val id = renderer.comment.id
        if (!collapsedIds.remove(id)) collapsedIds.add(id)
        inlay.update()
    }

    fun addInlineComment(@Suppress("UNUSED_PARAMETER") project: Project, editor: Editor, comment: ReviewComment) {
        val lineStart = (comment.lineStart ?: return) - 1
        val document = editor.document
        if (lineStart < 0 || lineStart >= document.lineCount) {
            log.warn("[CodeReview] addInlineComment SKIP: commentId=${comment.id}, lineStart=$lineStart out of range (lineCount=${document.lineCount})")
            return
        }

        val lineEnd = ((comment.lineEnd ?: comment.lineStart) - 1).coerceIn(0, document.lineCount - 1)
        val offset = document.getLineEndOffset(lineEnd)

        val renderer = InlineCommentRenderer(comment)
        val properties = InlayProperties()
            .relatesToPrecedingText(true)
            .showAbove(false)
            .priority(0)

        val inlay = editor.inlayModel.addBlockElement(offset, properties, renderer)
        if (inlay == null) {
            log.warn("[CodeReview] addInlineComment FAILED: addBlockElement returned null for commentId=${comment.id}, offset=$offset, editor=${System.identityHashCode(editor)}")
            return
        }
        log.warn("[CodeReview] addInlineComment OK: commentId=${comment.id}, line=${lineStart+1}, editor=${System.identityHashCode(editor)}, inlay=${System.identityHashCode(inlay)}")
        inlays[comment.id] = inlay
    }

    fun trackedEndLine(commentId: String, document: Document): Int? {
        val inlay = inlays[commentId]?.takeIf { it.isValid && it.editor.document == document } ?: return null
        return document.getLineNumber(inlay.offset) + 1
    }

    fun removeInlineComment(commentId: String) {
        inlays.remove(commentId)?.let { inlay ->
            try { inlay.dispose() } catch (_: Exception) {}
        }
    }

    fun updateInlineComment(project: Project, editor: Editor, comment: ReviewComment) {
        removeInlineComment(comment.id)
        addInlineComment(project, editor, comment)
    }

    fun clearAllInlineComments(project: Project) {
        val session = ReviewSessionService.getInstance(project).currentSession
        session.comments.forEach { removeInlineComment(it.id) }
        inlays.values.forEach { inlay ->
            try { inlay.dispose() } catch (_: Exception) {}
        }
        inlays.clear()
    }

    fun resyncAll(project: Project) {
        clearAllInlineComments(project)
        EditorFactory.getInstance().allEditors
            .filter { it.project == project && !it.isDisposed }
            .forEach { editor ->
                resolveCommentPath(project, editor)?.let { reapplyInlineComments(project, editor, it) }
            }
    }

    fun reapplyInlineComments(project: Project, editor: Editor, filePath: String) {
        val session = ReviewSessionService.getInstance(project).currentSession
        val matching = session.comments.filter { it.filePath == filePath && it.lineStart != null }
        log.warn("[CodeReview] reapplyInlineComments: filePath=$filePath, editor=${System.identityHashCode(editor)}, matchingComments=${matching.size}, totalInlays=${inlays.size}")
        matching.forEach { comment ->
                val existing = inlays[comment.id]
                log.warn("[CodeReview] reapplyInlineComments check: commentId=${comment.id}, existing=${existing != null}, isValid=${existing?.isValid}, existingEditor=${existing?.let { System.identityHashCode(it.editor) }}, currentEditor=${System.identityHashCode(editor)}, sameEditor=${existing?.editor == editor}")
                if (existing == null || !existing.isValid || existing.editor != editor) {
                    log.warn("[CodeReview] reapplyInlineComments RECREATING: commentId=${comment.id}")
                    existing?.let { try { it.dispose() } catch (_: Exception) {} }
                    inlays.remove(comment.id)
                    addInlineComment(project, editor, comment)
                } else {
                    log.warn("[CodeReview] reapplyInlineComments SKIPPED (already valid): commentId=${comment.id}")
                }
            }
    }

    /**
     * Shows a comment editor embedded below the given line for creating or editing a comment.
     * @param line1Based the 1-based line number to anchor below
     * @param existingComment if non-null, pre-fills the editor with this comment's data
     * @param onSave callback receiving (type, text) when the user saves
     */
    fun showEditorPopup(
        editor: Editor,
        line1Based: Int,
        existingComment: ReviewComment?,
        onSave: (CommentType, String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        closeCommentEditor(cancelled = true)
        val editorEx = editor as? EditorEx ?: return
        val project = editor.project ?: return

        val document = editor.document
        val line0 = (line1Based - 1).coerceIn(0, document.lineCount - 1)
        val offset = document.getLineEndOffset(line0)

        val panel = InlineCommentEditorPanel(
            project = project,
            initialType = existingComment?.type ?: CommentType.MUST_DO,
            initialText = existingComment?.text ?: "",
            onSave = { type, text ->
                closeCommentEditor(cancelled = false)
                onSave(type, text)
            },
            onCancel = { closeCommentEditor(cancelled = true) }
        )
        panel.fixedWidth = (editor.scrollingModel.visibleArea.width - EDITOR_MARGIN).coerceAtLeast(400)

        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editorEx,
            panel,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, false, 0, offset
            )
        ) ?: return
        currentCommentEditor = OpenCommentEditor(inlay, editor, onCancel)

        panel.onContentResized = {
            if (inlay.isValid) {
                panel.size = Dimension(panel.fixedWidth, panel.preferredSize.height)
                panel.revalidate()
                inlay.update()
            }
        }

        editor.scrollingModel.scrollTo(editor.offsetToLogicalPosition(offset), ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getInstance(project).requestFocus(panel.textField, true)
    }

    private class OpenCommentEditor(val inlay: Inlay<*>, val editor: Editor, val onCancel: (() -> Unit)?)

    private var currentCommentEditor: OpenCommentEditor? = null

    // Cancelling also runs when another editor replaces this one, so an edited
    // comment gets its display inlay back instead of vanishing.
    private fun closeCommentEditor(cancelled: Boolean) {
        val open = currentCommentEditor ?: return
        currentCommentEditor = null
        Disposer.dispose(open.inlay)
        if (cancelled) open.onCancel?.invoke()
        open.editor.project?.let { IdeFocusManager.getInstance(it).requestFocus(open.editor.contentComponent, true) }
    }

    private const val EDITOR_MARGIN = 40
}
