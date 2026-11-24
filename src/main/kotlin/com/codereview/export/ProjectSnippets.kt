package com.codereview.export

import com.codereview.model.ReviewComment
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

object ProjectSnippets {

    fun reader(project: Project): (ReviewComment) -> List<String>? = reader@{ comment ->
        val start = comment.lineStart ?: return@reader null
        val path = comment.filePath ?: return@reader null
        val vf = LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$path") ?: return@reader null
        runReadAction {
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@runReadAction null
            val end = (comment.lineEnd ?: start).coerceAtMost(document.lineCount)
            if (start < 1 || start > end) return@runReadAction null
            (start - 1 until end).map { line ->
                document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString()
            }
        }
    }
}
