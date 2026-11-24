package com.codereview.model

import java.util.UUID

// The label is for display only; persistence, export and agents use the enum name.
enum class CommentType(val label: String) {
    MUST_DO("Must do"),
    SUGGESTION("Suggestion"),
    QUESTION("Question")
}
enum class CommentScope { REVIEW, FILE, LINE }

data class ReviewComment(
    val id: String = UUID.randomUUID().toString(),
    val scope: CommentScope = CommentScope.REVIEW,
    val type: CommentType = CommentType.QUESTION,
    val text: String = "",
    val filePath: String? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    // The commented lines as they were when the comment was written.
    val anchorText: String? = null,
    val outdated: Boolean = false,
    val resolved: Boolean = false,
    val replies: List<CommentReply> = emptyList()
)

data class CommentReply(
    val author: String,
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ReviewSession(
    val id: String = UUID.randomUUID().toString(),
    val summary: String = "",
    val comments: MutableList<ReviewComment> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val baseBranch: String? = null
)

data class ArchivedReview(
    val session: ReviewSession,
    val archivedAt: Long = System.currentTimeMillis()
)
