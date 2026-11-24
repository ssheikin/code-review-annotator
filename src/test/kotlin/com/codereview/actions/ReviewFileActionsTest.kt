package com.codereview.actions

import com.codereview.model.*
import com.codereview.service.ReviewSessionService
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.junit.Assert.*
import org.junit.Test

class ReviewFileActionsTest {

    private fun stateWithNullLineEnd(): ReviewSessionService.State {
        val service = ReviewSessionService(null)
        service.addComment(ReviewComment(id = "c1", scope = CommentScope.LINE, text = "Bug", filePath = "a.go", lineStart = 3))
        return service.state
    }

    private fun load(element: Element): ReviewComment {
        val service = ReviewSessionService(null)
        service.loadState(deserializeReviews(element))
        return service.currentSession.comments.single()
    }

    @Test
    fun `export and import keep null optional fields`() {
        val restored = load(serializeReviews(stateWithNullLineEnd()))
        assertEquals(3, restored.lineStart)
        assertNull(restored.lineEnd)
    }

    @Test
    fun `import reads files that hold empty tags for null fields`() {
        val restored = load(XmlSerializer.serialize(stateWithNullLineEnd()))
        assertEquals("Bug", restored.text)
        assertNull(restored.lineEnd)
    }
}
