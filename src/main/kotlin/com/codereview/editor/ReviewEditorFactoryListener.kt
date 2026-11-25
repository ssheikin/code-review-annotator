package com.codereview.editor

import com.codereview.service.ReviewSessionService
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Startup activity that registers an [EditorFactoryListener] to reapply
 * review comments whenever a new editor is created.
 *
 * We register programmatically rather than via XML `applicationListeners`
 * because IntelliJ 2024.1+ changed the dispatch signature from
 * `editorCreated(EditorFactoryEvent)` to `editorCreated(Editor)`.
 * Programmatic registration via [EditorFactory.addEditorFactoryListener]
 * works across all platform versions.
 */
class ReviewEditorListenerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val listener = ReviewEditorFactoryListenerImpl()
        EditorFactory.getInstance().addEditorFactoryListener(listener, project)
        CommentAnchors.install(project, project)
        // Tabs restored at project open exist before this activity runs, so the listener never saw them.
        withContext(Dispatchers.EDT) {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project && !it.isDisposed }
                .forEach { listener.handleEditorCreated(it) }
        }
    }
}

private class ReviewEditorFactoryListenerImpl : EditorFactoryListener {

    private val log = Logger.getInstance(ReviewEditorFactoryListenerImpl::class.java)

    companion object {
        private val CLICK_HANDLER_INSTALLED = Key.create<Boolean>("codereview.clickHandlerInstalled")
        private val HOVER_HANDLER_INSTALLED = Key.create<Boolean>("codereview.hoverHandlerInstalled")
        private val BUTTON_HOVER_HANDLER_INSTALLED = Key.create<Boolean>("codereview.buttonHoverHandlerInstalled")
        private val HANDLED = Key.create<Boolean>("codereview.editorCreatedHandled")
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        handleEditorCreated(event.editor)
    }

    fun handleEditorCreated(editor: Editor) {
        if (editor.getUserData(HANDLED) == true) return
        editor.putUserData(HANDLED, true)

        val project = editor.project ?: run {
            log.warn("[CodeReview] editorCreated: SKIP — editor.project is null, editor=${System.identityHashCode(editor)}, docClass=${editor.document::class.java.name}")
            return
        }
        val session = ReviewSessionService.getInstance(project).currentSession
        val matchedPath = resolveCommentPath(project, editor) ?: return

        log.warn("[CodeReview] editorCreated: matchedPath=$matchedPath, editor=${System.identityHashCode(editor)}, commentsForPath=${session.comments.count { it.filePath == matchedPath }}")
        InlineCommentManager.reapplyInlineComments(project, editor, matchedPath)

        if (editor.getUserData(CLICK_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseListener(InlineCommentClickHandler(project))
            editor.putUserData(CLICK_HANDLER_INSTALLED, true)
        }

        if (editor.getUserData(HOVER_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseMotionListener(AddCommentGutterHoverHandler(project))
            editor.putUserData(HOVER_HANDLER_INSTALLED, true)
        }

        if (editor.getUserData(BUTTON_HOVER_HANDLER_INSTALLED) != true) {
            editor.addEditorMouseMotionListener(InlineCommentHoverHandler())
            editor.putUserData(BUTTON_HOVER_HANDLER_INSTALLED, true)
        }
    }
}

private val resolveLog = Logger.getInstance("com.codereview.editor.ResolveCommentPath")

// Console viewers are created on the EDT outside a read action, and looking up documents needs one.
internal fun resolveCommentPath(project: Project, editor: Editor): String? = runReadAction {
    val projectBasePath = project.basePath ?: return@runReadAction null
    val session = ReviewSessionService.getInstance(project).currentSession

    val vfile = FileDocumentManager.getInstance().getFile(editor.document)
    resolveLog.warn("[CodeReview] resolveCommentPath: editor=${System.identityHashCode(editor)}, document=${System.identityHashCode(editor.document)}, docClass=${editor.document::class.java.name}, vfile=${vfile?.path}, vfileClass=${vfile?.javaClass?.name}, isDisposed=${editor.isDisposed}, totalComments=${session.comments.size}")

    // Reverse lookup: find which comment filePath matches this editor's document
    val commentPaths = session.comments
        .mapNotNull { it.filePath }
        .distinct()

    for (relativePath in commentPaths) {
        val fullPath = "$projectBasePath/$relativePath"
        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath) ?: continue
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: continue
        resolveLog.warn("[CodeReview] resolveCommentPath check: relativePath=$relativePath, docFromVf=${System.identityHashCode(doc)}, editorDoc=${System.identityHashCode(editor.document)}, same=${doc === editor.document}")
        if (doc === editor.document) return@runReadAction relativePath
    }

    // Fallback for regular editors (also handles files with no comments yet)
    val file = vfile ?: run {
        resolveLog.warn("[CodeReview] resolveCommentPath: SKIP — FileDocumentManager.getFile() returned null for document ${System.identityHashCode(editor.document)}")
        return@runReadAction null
    }
    file.path.let {
        if (it.startsWith(projectBasePath)) it.removePrefix(projectBasePath).removePrefix("/") else it
    }
}
