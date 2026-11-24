package com.codereview.model

import java.util.UUID

enum class CommentType { MUST_DO, SUGGESTION, QUESTION }
enum class CommentScope { REVIEW, FILE, LINE }

data class ReviewComment(
    val id: String = UUID.randomUUID().toString(),
    val scope: CommentScope = CommentScope.REVIEW,
    val type: CommentType = CommentType.QUESTION,
    val text: String = "",
    val filePath: String? = null,
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
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
