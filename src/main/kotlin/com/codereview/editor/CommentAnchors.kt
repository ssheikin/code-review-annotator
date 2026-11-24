package com.codereview.editor

import com.codereview.model.AnchorMatching
import com.codereview.model.CommentScope
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.Alarm

object CommentAnchors {

    fun capture(document: Document, lineStart: Int, lineEnd: Int): String =
        (lineStart - 1 until lineEnd.coerceAtMost(document.lineCount)).joinToString("\n") { line ->
            document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
        }

    fun reanchor(project: Project, document: Document, path: String): Boolean {
        val service = ReviewSessionService.getInstance(project)
        val lines = document.text.split("\n")
        var changed = false
        service.currentSession.comments
            .filter { it.scope == CommentScope.LINE && it.filePath == path && it.anchorText != null && it.lineStart != null }
            .forEach { comment ->
                val anchor = comment.anchorText!!.split("\n")
                // The inlay follows in-IDE edits, which keeps the position right while the commented code itself is being edited.
                val tracked = InlineCommentManager.trackedEndLine(comment.id, document)?.let { it - anchor.size + 1 }
                val guess = tracked ?: comment.lineStart!!
                val located = if (AnchorMatching.matchesAt(lines, anchor, guess)) guess else AnchorMatching.locate(lines, anchor, guess)
                val start = (located ?: guess).coerceIn(1, maxOf(1, lines.size))
                val end = if (anchor.size > 1) start + anchor.size - 1 else null
                val outdated = located == null
                if (start != comment.lineStart || end != comment.lineEnd || outdated != comment.outdated) {
                    service.relocate(comment.id, start, end, outdated)
                    changed = true
                }
            }
        return changed
    }

    fun reanchorAll(project: Project) {
        val basePath = project.basePath ?: return
        val paths = ReviewSessionService.getInstance(project).currentSession.comments
            .filter { it.scope == CommentScope.LINE }
            .mapNotNull { it.filePath }
            .distinct()
        for (path in paths) {
            val vf = LocalFileSystem.getInstance().findFileByPath("$basePath/$path") ?: continue
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: continue
            reanchor(project, document, path)
        }
    }

    fun install(project: Project, parent: Disposable) {
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
        val pending = mutableSetOf<Document>()
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pending.add(event.document)
                alarm.cancelAllRequests()
                alarm.addRequest({
                    val basePath = project.basePath ?: return@addRequest
                    var changed = false
                    pending.forEach { document ->
                        val path = FileDocumentManager.getInstance().getFile(document)?.path
                            ?.takeIf { it.startsWith(basePath) }
                            ?.removePrefix(basePath)?.removePrefix("/")
                            ?: return@forEach
                        changed = reanchor(project, document, path) || changed
                    }
                    pending.clear()
                    if (changed) {
                        InlineCommentManager.resyncAll(project)
                        ReviewToolWindowFactory.refreshPanel(project)
                    }
                }, 300)
            }
        }, parent)
    }
}
