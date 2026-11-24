package com.codereview.service

import com.codereview.model.*
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

interface ReviewCommentListener {
    fun commentsChanged()

    companion object {
        val TOPIC: Topic<ReviewCommentListener> =
            Topic.create("ReviewCommentChanged", ReviewCommentListener::class.java)
    }
}

@State(
    name = "CodeReviewAnnotator",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project?) : PersistentStateComponent<ReviewSessionService.State> {

    class CommentState {
        @Tag("id") var id: String = ""
        @Tag("scope") var scope: String = "REVIEW"
        @Tag("type") var type: String = "QUESTION"
        @Tag("text") var text: String = ""
        @Tag("filePath") var filePath: String? = null
        @Tag("lineStart") var lineStart: Int? = null
        @Tag("lineEnd") var lineEnd: Int? = null
        @Tag("createdAt") var createdAt: Long = 0L
    }

    class State {
        @Tag("sessionId") var sessionId: String = ""
        @Tag("sessionCreatedAt") var sessionCreatedAt: Long = 0L
        @Tag("baseBranch") var baseBranch: String? = null
        @Tag("summary") var summary: String = ""
        @XCollection(elementName = "comment") var comments: MutableList<CommentState> = mutableListOf()
    }

    private data class Snapshot(val comments: List<ReviewComment>, val summary: String)
    private class Change(val before: Snapshot, val after: Snapshot)

    private var session: ReviewSession = ReviewSession()
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    val currentSession: ReviewSession get() = session
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun snapshot() = Snapshot(session.comments.toList(), session.summary)

    private inline fun recorded(change: () -> Unit) {
        val before = snapshot()
        change()
        val after = snapshot()
        if (before == after) return
        undoStack.addLast(Change(before, after))
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo() {
        val change = undoStack.removeLastOrNull() ?: return
        restore(change.before, change)
        redoStack.addLast(change)
    }

    fun redo() {
        val change = redoStack.removeLastOrNull() ?: return
        restore(change.after, change)
        undoStack.addLast(change)
    }

    // Summary typing is not recorded, so restoring it from an unrelated
    // change would silently drop what the user typed since.
    private fun restore(target: Snapshot, change: Change) {
        val summary = if (change.before.summary != change.after.summary) target.summary else session.summary
        session = session.copy(comments = target.comments.toMutableList(), summary = summary)
        fireCommentsChanged()
    }

    private fun fireCommentsChanged() {
        project?.messageBus?.syncPublisher(ReviewCommentListener.TOPIC)?.commentsChanged()
    }

    fun addComment(comment: ReviewComment) {
        recorded { session.comments.add(comment) }
        fireCommentsChanged()
    }

    fun removeComment(commentId: String) {
        recorded { session.comments.removeAll { it.id == commentId } }
        fireCommentsChanged()
    }

    fun updateComment(commentId: String, updater: (ReviewComment) -> ReviewComment): ReviewComment? {
        val index = session.comments.indexOfFirst { it.id == commentId }
        if (index >= 0) {
            recorded { session.comments[index] = updater(session.comments[index]) }
            fireCommentsChanged()
            return session.comments[index]
        }
        return null
    }

    fun updateSummary(text: String) {
        session = session.copy(summary = text)
    }

    fun clearSession() {
        recorded { session = ReviewSession() }
        fireCommentsChanged()
    }

    override fun getState(): State {
        val state = State()
        state.sessionId = session.id
        state.sessionCreatedAt = session.createdAt
        state.baseBranch = session.baseBranch
        state.summary = session.summary
        state.comments = session.comments.map { c ->
            CommentState().apply {
                id = c.id
                scope = c.scope.name
                type = c.type.name
                text = c.text
                filePath = c.filePath
                lineStart = c.lineStart
                lineEnd = c.lineEnd
                createdAt = c.createdAt
            }
        }.toMutableList()
        return state
    }

    override fun loadState(state: State) {
        val comments = state.comments.map { c ->
            ReviewComment(
                id = c.id,
                scope = CommentScope.valueOf(c.scope),
                type = CommentType.valueOf(c.type),
                text = c.text,
                filePath = c.filePath,
                lineStart = c.lineStart,
                lineEnd = c.lineEnd,
                createdAt = c.createdAt
            )
        }.toMutableList()
        // Migrate legacy REVIEW-scoped comments into the summary field
        val migratedSummary = if (state.summary.isEmpty()) {
            comments.filter { it.scope == CommentScope.REVIEW }.joinToString("\n\n") { it.text }
        } else {
            state.summary
        }
        comments.removeAll { it.scope == CommentScope.REVIEW }
        session = ReviewSession(
            id = state.sessionId.ifEmpty { session.id },
            summary = migratedSummary,
            comments = comments,
            createdAt = if (state.sessionCreatedAt > 0) state.sessionCreatedAt else System.currentTimeMillis(),
            baseBranch = state.baseBranch
        )
    }

    companion object {
        private const val MAX_UNDO = 100

        fun getInstance(project: Project): ReviewSessionService =
            project.getService(ReviewSessionService::class.java)
    }
}
