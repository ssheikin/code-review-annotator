package com.codereview.ui

import com.codereview.model.CommentType
import com.intellij.ui.JBColor
import java.awt.Color

// One value for both themes: the badge draws white text on it, and a lighter dark-theme
// variant would drop below 4.5:1 contrast.
val CommentType.accentColor: Color
    get() = when (this) {
        CommentType.MUST_DO -> Color(0, 110, 230)
        CommentType.SUGGESTION -> Color(175, 91, 11)
        CommentType.QUESTION -> Color(152, 71, 222)
    }

val CommentType.inlayBackground: Color
    get() = when (this) {
        CommentType.MUST_DO -> JBColor(Color(212, 232, 255), Color(8, 42, 84))
        CommentType.SUGGESTION -> JBColor(Color(254, 232, 218), Color(61, 37, 20))
        CommentType.QUESTION -> JBColor(Color(241, 232, 254), Color(48, 37, 61))
    }

val CommentType.listBackground: Color
    get() = when (this) {
        CommentType.MUST_DO -> JBColor(Color(232, 243, 255), Color(12, 32, 62))
        CommentType.SUGGESTION -> JBColor(Color(254, 243, 236), Color(45, 30, 20))
        CommentType.QUESTION -> JBColor(Color(247, 243, 254), Color(37, 30, 45))
    }
