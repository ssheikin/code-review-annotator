package com.codereview.service

import com.codereview.model.*
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        @Tag("anchorText") var anchorText: String? = null
        @Tag("outdated") var outdated: Boolean = false
        @Tag("resolved") var resolved: Boolean = false
        @XCollection(elementName = "reply") var replies: MutableList<ReplyState> = mutableListOf()
    }

    class ReplyState {
        @Tag("author") var author: String = ""
        @Tag("text") var text: String = ""
        @Tag("createdAt") var createdAt: Long = 0L
    }

    class State {
        @Tag("sessionId") var sessionId: String = ""
        @Tag("sessionCreatedAt") var sessionCreatedAt: Long = 0L
        @Tag("baseBranch") var baseBranch: String? = null
        @Tag("summary") var summary: String = ""
        @XCollection(elementName = "comment") var comments: MutableList<CommentState> = mutableListOf()
        @XCollection(elementName = "archivedReview") var history: MutableList<ArchivedState> = mutableListOf()
    }

    class ArchivedState {
        @Tag("sessionId") var sessionId: String = ""
        @Tag("sessionCreatedAt") var sessionCreatedAt: Long = 0L
        @Tag("archivedAt") var archivedAt: Long = 0L
        @Tag("summary") var summary: String = ""
        @XCollection(elementName = "comment") var comments: MutableList<CommentState> = mutableListOf()
    }

    private data class Snapshot(val comments: List<ReviewComment>, val summary: String)
    private class Change(val before: Snapshot, val after: Snapshot)

    private var session: ReviewSession = ReviewSession()
    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()
    private val history = mutableListOf<ArchivedReview>()

    private val _changeCount = MutableStateFlow(0L)

    val currentSession: ReviewSession get() = session

    // Counts recorded changes only, so code moving under a comment does not wake an agent waiting for review input.
    val changeCount: StateFlow<Long> get() = _changeCount
    val reviewHistory: List<ArchivedReview> get() = history
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    private fun snapshot() = Snapshot(session.comments.toList(), session.summary)

    private inline fun recorded(change: () -> Unit) {
        val before = snapshot()
        change()
        val after = snapshot()
        if (before == after) return
        _changeCount.value++
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

    fun setResolved(commentId: String, resolved: Boolean): ReviewComment? =
        updateComment(commentId) { it.copy(resolved = resolved) }

    fun addReply(commentId: String, author: String, text: String): ReviewComment? =
        updateComment(commentId) { it.copy(replies = it.replies + CommentReply(author, text)) }

    // Not recorded: the code moved, the reviewer did not change anything.
    fun relocate(commentId: String, lineStart: Int, lineEnd: Int?, outdated: Boolean) {
        val index = session.comments.indexOfFirst { it.id == commentId }
        if (index < 0) return
        session.comments[index] = session.comments[index].copy(lineStart = lineStart, lineEnd = lineEnd, outdated = outdated)
        fireCommentsChanged()
    }

    fun updateSummary(text: String) {
        session = session.copy(summary = text)
    }

    fun clearSession() {
        recorded { session = ReviewSession() }
        fireCommentsChanged()
    }

    fun archiveAndClear() {
        archiveCurrent()
        clearSession()
    }

    // Kept apart from clearing so a waiting agent can tell a submitted review from a discarded one.
    var lastSubmitted: ArchivedReview? = null
        private set

    fun submitAndClear() {
        archiveCurrent()
        lastSubmitted = history.firstOrNull()
        clearSession()
    }

    // Undo is in memory only, so a review replaced without archiving
    // would be lost on restart.
    private fun archiveCurrent() {
        if (session.comments.isEmpty() && session.summary.isEmpty()) return
        history.add(0, ArchivedReview(session.copy(comments = session.comments.toMutableList())))
        if (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
    }

    // Imported history is merged so that moving reviews between machines
    // or projects never drops entries already here.
    fun importState(imported: State) {
        val other = ReviewSessionService(null).also { it.loadState(imported) }
        val known = history.map { it.session.id }.toSet()
        history.addAll(other.history.filter { it.session.id !in known })
        history.sortByDescending { it.archivedAt }
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)

        val incoming = other.currentSession
        val unchanged = incoming.comments == session.comments && incoming.summary == session.summary
        if (!unchanged && (incoming.comments.isNotEmpty() || incoming.summary.isNotEmpty())) {
            archiveCurrent()
            recorded { session = incoming }
        }
        fireCommentsChanged()
    }

    // A fresh id keeps the restored review distinct from its archived
    // original, which import deduplicates by id.
    fun restoreFromHistory(entry: ArchivedReview) {
        archiveCurrent()
        recorded {
            session = entry.session.copy(id = UUID.randomUUID().toString(), comments = entry.session.comments.toMutableList())
        }
        fireCommentsChanged()
    }

    override fun getState(): State {
        val state = State()
        state.sessionId = session.id
        state.sessionCreatedAt = session.createdAt
        state.baseBranch = session.baseBranch
        state.summary = session.summary
        state.comments = session.comments.map { it.toState() }.toMutableList()
        state.history = history.map { entry ->
            ArchivedState().apply {
                sessionId = entry.session.id
                sessionCreatedAt = entry.session.createdAt
                archivedAt = entry.archivedAt
                summary = entry.session.summary
                comments = entry.session.comments.map { it.toState() }.toMutableList()
            }
        }.toMutableList()
        return state
    }

    override fun loadState(state: State) {
        val comments = state.comments.map { it.toComment() }.toMutableList()
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
        history.clear()
        state.history.mapTo(history) { a ->
            ArchivedReview(
                session = ReviewSession(
                    id = a.sessionId,
                    summary = a.summary,
                    comments = a.comments.map { it.toComment() }.toMutableList(),
                    createdAt = a.sessionCreatedAt
                ),
                archivedAt = a.archivedAt
            )
        }
    }

    companion object {
        private const val MAX_UNDO = 100
        private const val MAX_HISTORY = 50

        private fun ReviewComment.toState() = CommentState().also { s ->
            s.id = id
            s.scope = scope.name
            s.type = type.name
            s.text = text
            s.filePath = filePath
            s.lineStart = lineStart
            s.lineEnd = lineEnd
            s.createdAt = createdAt
            s.anchorText = anchorText
            s.outdated = outdated
            s.resolved = resolved
            s.replies = replies.map { r ->
                ReplyState().apply { author = r.author; text = r.text; createdAt = r.createdAt }
            }.toMutableList()
        }

        private fun CommentState.toComment() = ReviewComment(
            id = id,
            scope = CommentScope.valueOf(scope),
            type = CommentType.valueOf(type),
            text = text,
            filePath = filePath,
            lineStart = lineStart,
            lineEnd = lineEnd,
            createdAt = createdAt,
            anchorText = anchorText,
            outdated = outdated,
            resolved = resolved,
            replies = replies.map { CommentReply(it.author, it.text, it.createdAt) }
        )

        fun getInstance(project: Project): ReviewSessionService =
            project.getService(ReviewSessionService::class.java)
    }
}
