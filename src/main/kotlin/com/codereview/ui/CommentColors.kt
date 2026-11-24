package com.codereview.ui

import com.codereview.model.CommentType
import com.intellij.ui.JBColor
import java.awt.Color

val CommentType.accentColor: Color
    get() = when (this) {
        CommentType.MUST_DO -> JBColor(Color(220, 50, 50), Color(220, 80, 80))
        CommentType.SUGGESTION -> JBColor(Color(50, 100, 220), Color(80, 130, 255))
        CommentType.QUESTION -> JBColor(Color(200, 160, 0), Color(220, 180, 50))
    }

val CommentType.inlayBackground: Color
    get() = when (this) {
        CommentType.MUST_DO -> JBColor(Color(255, 235, 235), Color(60, 30, 30))
        CommentType.SUGGESTION -> JBColor(Color(235, 240, 255), Color(30, 35, 60))
        CommentType.QUESTION -> JBColor(Color(255, 250, 230), Color(55, 50, 30))
    }

val CommentType.listBackground: Color
    get() = when (this) {
        CommentType.MUST_DO -> JBColor(Color(255, 245, 245), Color(45, 25, 25))
        CommentType.SUGGESTION -> JBColor(Color(245, 247, 255), Color(25, 28, 45))
        CommentType.QUESTION -> JBColor(Color(255, 252, 240), Color(42, 40, 25))
    }
