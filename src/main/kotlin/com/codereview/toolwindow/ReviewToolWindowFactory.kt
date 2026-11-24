package com.codereview.toolwindow

import com.codereview.actions.CommentDialog
import com.codereview.actions.RedoReviewChangeAction
import com.codereview.actions.UndoReviewChangeAction
import com.codereview.editor.InlineCommentEditorPanel
import com.codereview.editor.InlineCommentManager
import com.codereview.model.CommentScope
import com.codereview.model.CommentType
import com.codereview.model.ReviewComment
import com.codereview.service.ReviewSessionService
import com.codereview.ui.accentColor
import com.codereview.ui.listBackground
import com.intellij.icons.AllIcons
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.PathUtil
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.text.DateFormatUtil
import com.intellij.vcsUtil.VcsUtil
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

class ReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)

        val actionGroup = ActionManager.getInstance()
            .getAction("CodeReviewAnnotator.ToolWindowToolbar") as? com.intellij.openapi.actionSystem.ActionGroup
        val wrapper = JPanel(BorderLayout())
        if (actionGroup != null) {
            val toolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)
            toolbar.targetComponent = panel
            wrapper.add(toolbar.component, BorderLayout.NORTH)
        }
        wrapper.add(panel, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(wrapper, "Comments", false)
        toolWindow.contentManager.addContent(content)
        panels[project] = panel
    }

    companion object {
        private val panels = mutableMapOf<Project, ReviewPanel>()

        fun refreshPanel(project: Project) {
            panels[project]?.refresh()
        }
    }
}

private sealed interface ReviewListItem {
    data class Header(val groupTitle: String) : ReviewListItem
    data class Comment(val comment: ReviewComment) : ReviewListItem
}

class ReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<ReviewListItem>()
    private val commentList = JBList<ReviewListItem>(listModel)
    private val cellRenderer = ReviewListCellRenderer()
    private val summaryArea = JTextArea(4, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        toolTipText = "Review summary"
    }
    private var updatingSummary = false
    private var lastSelectedIndex = -1

    init {
        commentList.cellRenderer = cellRenderer
        commentList.fixedCellHeight = -1

        // Suppress selection on header items
        commentList.addListSelectionListener(ListSelectionListener { e ->
            if (e.valueIsAdjusting) return@ListSelectionListener
            // Mouse clicks navigate on their own, so only the arrow keys open the code here.
            val byKeyboard = IdeEventQueue.getInstance().trueCurrentEvent is KeyEvent
            val index = commentList.selectedIndex
            if (index >= 0 && listModel.getElementAt(index) is ReviewListItem.Header) {
                // Arrow keys step over file headers instead of stopping on them.
                val next = if (index > lastSelectedIndex) index + 1 else index - 1
                val target = if (next in 0 until listModel.size() && listModel.getElementAt(next) is ReviewListItem.Comment) next else lastSelectedIndex
                if (byKeyboard && target >= 0) {
                    commentList.selectedIndex = target
                    commentList.ensureIndexIsVisible(target)
                } else {
                    commentList.clearSelection()
                }
                return@ListSelectionListener
            }
            lastSelectedIndex = index
            if (byKeyboard) selectedComment()?.let { navigateToComment(it, focusEditor = false) }
        })

        commentList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val hit = hitCell(e.point) ?: return
                when {
                    // The second click of a double-click would delete the row that moved up under the pointer.
                    hit.cell.isDeleteHit(hit.x, hit.y) -> if (e.clickCount == 1) deleteComment(hit.comment)
                    hit.cell.isBadgeHit(hit.x, hit.y) -> if (e.clickCount == 1) showTypePopup(hit)
                    // A single click keeps focus in the list, so its shortcuts still apply to the row.
                    e.clickCount == 1 -> navigateToComment(hit.comment, focusEditor = false)
                    e.clickCount == 2 -> editComment(hit.comment)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                setHover(-1, false)
            }
        })

        commentList.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val hit = updateHover(e.point)
                commentList.cursor = if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        })

        val deleteAction = object : DumbAwareAction("Delete", null, AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedComment()?.let { deleteComment(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedComment() != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        deleteAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), null), KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), null)),
            commentList
        )
        val typeActions = InlineCommentEditorPanel.installTypeShortcuts(
            commentList, { selectedComment() != null }, { selectedComment()?.type == it }
        ) { type ->
            selectedComment()?.let { changeType(it, type) }
        }
        val editAction = object : DumbAwareAction("Edit", null, AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedComment()?.let { editComment(it) }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedComment() != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        editAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null), KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), null)),
            commentList
        )

        // The same action instances as the shortcuts, so the menu shows their keys.
        val contextGroup = DefaultActionGroup(
            listOf(editAction, Separator.getInstance()) + typeActions + Separator.getInstance() + deleteAction
        )
        commentList.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: Component, x: Int, y: Int) {
                val hit = hitCell(Point(x, y)) ?: return
                commentList.selectedIndex = hit.index
                ActionManager.getInstance()
                    .createActionPopupMenu(ActionPlaces.POPUP, contextGroup)
                    .component.show(comp, x, y)
            }
        })

        // The platform undo/redo shortcuts act on the review when the list has focus.
        val actionManager = ActionManager.getInstance()
        UndoReviewChangeAction().registerCustomShortcutSet(
            actionManager.getAction(IdeActions.ACTION_UNDO).shortcutSet, commentList
        )
        RedoReviewChangeAction().registerCustomShortcutSet(
            actionManager.getAction(IdeActions.ACTION_REDO).shortcutSet, commentList
        )

        // Summary area document listener
        summaryArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun removeUpdate(e: DocumentEvent) = onSummaryChanged()
            override fun changedUpdate(e: DocumentEvent) = onSummaryChanged()
        })

        // Bottom panel: label + scrollable text area
        val summaryLabel = JLabel("Review summary").apply {
            border = BorderFactory.createEmptyBorder(4, 8, 2, 8)
            font = font.deriveFont(Font.BOLD)
        }
        val summarySeparator = JSeparator(SwingConstants.HORIZONTAL)
        val summaryScrollPane = JBScrollPane(summaryArea)
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(summarySeparator, BorderLayout.NORTH)
            add(summaryLabel, BorderLayout.CENTER)
            add(summaryScrollPane, BorderLayout.SOUTH)
        }

        add(JBScrollPane(commentList), BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
        refresh()
    }

    private fun onSummaryChanged() {
        if (updatingSummary) return
        ReviewSessionService.getInstance(project).updateSummary(summaryArea.text)
    }

    fun refresh() {
        listModel.clear()
        val session = ReviewSessionService.getInstance(project).currentSession
        val sorted = session.comments
            .filter { it.scope != CommentScope.REVIEW }
            .sortedWith(
                compareBy<ReviewComment> { it.scope.ordinal }
                    .thenBy { it.filePath ?: "" }
                    .thenBy { it.lineStart ?: 0 }
            )

        val grouped = sorted.groupBy { comment ->
            comment.filePath ?: "Unknown"
        }

        for ((groupTitle, comments) in grouped) {
            listModel.addElement(ReviewListItem.Header(groupTitle))
            comments.forEach { listModel.addElement(ReviewListItem.Comment(it)) }
        }

        // Sync summary text area without triggering the document listener
        updatingSummary = true
        try {
            if (summaryArea.text != session.summary) {
                summaryArea.text = session.summary
            }
        } finally {
            updatingSummary = false
        }
        updateHover(commentList.mousePosition)
    }

    private fun navigateToComment(comment: ReviewComment, focusEditor: Boolean): Editor? {
        val filePath = comment.filePath ?: return null
        val basePath = project.basePath ?: return null
        val fullPath = "$basePath/$filePath"
        val line = ((comment.lineStart ?: 1) - 1).coerceAtLeast(0)

        val vf = LocalFileSystem.getInstance().findFileByPath(fullPath)
        if (vf != null) {
            val descriptor = OpenFileDescriptor(project, vf, line, 0)
            return FileEditorManager.getInstance(project).openTextEditor(descriptor, focusEditor)
        }

        // A deleted file survives only as the left side of its diff.
        val change = ChangeListManager.getInstance(project).getChange(VcsUtil.getFilePath(fullPath, false)) ?: return null
        val context = mapOf<Key<*>, Any>(DiffUserDataKeys.SCROLL_TO_LINE to Pair.create(Side.LEFT, line))
        val producer = ChangeDiffRequestProducer.create(project, change, context) ?: return null
        val chain = ChangeDiffRequestChain(listOf(producer), 0)
        // Opened as a plain editor file, because the diff manager may put it in a separate window.
        FileEditorManager.getInstance(project).openFile(ChainDiffVirtualFile(chain, PathUtil.getFileName(filePath)), focusEditor)
        return null
    }

    private fun editComment(comment: ReviewComment) {
        if (comment.scope == CommentScope.LINE) {
            // A deleted file opens as a diff, which has no editor to hold the comment editor.
            // Focus goes from the list straight to the comment editor, not via the main editor.
            val editor = navigateToComment(comment, focusEditor = false) ?: return
            InlineCommentManager.editComment(project, editor, comment)
            return
        }
        // File comments have no line to anchor the inline editor to.
        val dialog = CommentDialog(project, "Edit File Comment", comment.type, comment.text, filePath = comment.filePath)
        if (!dialog.showAndGet()) return
        ReviewSessionService.getInstance(project).updateComment(comment.id) {
            it.copy(type = dialog.selectedType, text = dialog.commentText)
        }
        refresh()
    }

    private fun deleteComment(comment: ReviewComment) {
        InlineCommentManager.removeInlineComment(comment.id)
        ReviewSessionService.getInstance(project).removeComment(comment.id)
        refresh()
    }

    private class CellHit(val index: Int, val comment: ReviewComment, val cellBounds: Rectangle, val cell: CommentCellPanel, val x: Int, val y: Int)

    private fun hitCell(point: Point): CellHit? {
        val index = commentList.locationToIndex(point)
        if (index < 0) return null
        val cellBounds = commentList.getCellBounds(index, index) ?: return null
        if (!cellBounds.contains(point)) return null
        val comment = (listModel.getElementAt(index) as? ReviewListItem.Comment)?.comment ?: return null

        // The row under the pointer always shows its delete button.
        val renderer = CommentCellPanel()
        renderer.configure(comment, false, commentList, showDelete = true, deleteHovered = false)
        renderer.setSize(cellBounds.width, cellBounds.height)
        renderer.doLayout()
        // Force a paint to compute deleteBtnBounds and badgeBounds
        val img = java.awt.image.BufferedImage(cellBounds.width, cellBounds.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        renderer.paintComponent(g)
        g.dispose()
        return CellHit(index, comment, cellBounds, renderer, point.x - cellBounds.x, point.y - cellBounds.y)
    }

    // Also called after refresh, because rows under a still pointer change when comments come and go.
    private fun updateHover(point: Point?): CellHit? {
        val hit = point?.let { hitCell(it) }
        setHover(hit?.index ?: -1, hit != null && hit.cell.isDeleteHit(hit.x, hit.y))
        return hit
    }

    private fun setHover(index: Int, overDelete: Boolean) {
        if (cellRenderer.hoveredIndex == index && cellRenderer.deleteHovered == overDelete) return
        cellRenderer.hoveredIndex = index
        cellRenderer.deleteHovered = overDelete
        commentList.repaint()
    }

    private fun selectedComment(): ReviewComment? = (commentList.selectedValue as? ReviewListItem.Comment)?.comment

    private fun showTypePopup(hit: CellHit) {
        val badge = hit.cell.badgeBounds ?: return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(CommentType.entries)
            .setRenderer(object : ColoredListCellRenderer<CommentType>() {
                override fun customizeCellRenderer(
                    list: JList<out CommentType>, value: CommentType?, index: Int, selected: Boolean, hasFocus: Boolean
                ) {
                    value ?: return
                    // A colour chip rather than coloured text, since the accents are too dark to read on dark popups.
                    icon = ColorIcon(JBUI.scale(10), value.accentColor)
                    append(value.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${InlineCommentEditorPanel.typeShortcutText(value)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setSelectedValue(hit.comment.type, true)
            .setItemChosenCallback { changeType(hit.comment, it) }
            .createPopup()
            .show(RelativePoint(commentList, Point(hit.cellBounds.x + badge.x, hit.cellBounds.y + badge.y + badge.height)))
    }

    private fun changeType(comment: ReviewComment, type: CommentType) {
        // The popup can outlive the comment it was opened for, through undo or an agent edit.
        val current = ReviewSessionService.getInstance(project).currentSession.comments.find { it.id == comment.id } ?: return
        if (current.type == type) return
        ReviewSessionService.getInstance(project).updateComment(comment.id) { it.copy(type = type) }
        InlineCommentManager.showTypeInOpenEditor(comment.id, type)
        // The list knows no editor for the comment, so every inlay is rebuilt, as undo does.
        InlineCommentManager.resyncAll(project)
        refresh()
        // Keeps Alt+1..3 working on the same row, since refresh drops the selection.
        val index = (0 until listModel.size()).firstOrNull {
            (listModel.getElementAt(it) as? ReviewListItem.Comment)?.comment?.id == comment.id
        }
        if (index != null) commentList.selectedIndex = index
    }
}

private class ReviewListCellRenderer : ListCellRenderer<ReviewListItem> {
    private val headerPanel = HeaderCellPanel()
    private val commentPanel = CommentCellPanel()

    var hoveredIndex = -1
    var deleteHovered = false

    override fun getListCellRendererComponent(
        list: JList<out ReviewListItem>,
        value: ReviewListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        return when (value) {
            is ReviewListItem.Header -> {
                headerPanel.configure(value.groupTitle)
                headerPanel
            }
            is ReviewListItem.Comment -> {
                commentPanel.configure(
                    value.comment, isSelected, list,
                    showDelete = isSelected || index == hoveredIndex,
                    deleteHovered = deleteHovered && index == hoveredIndex
                )
                commentPanel
            }
        }
    }
}

private class HeaderCellPanel : JPanel() {

    private var title: String = ""

    init {
        isOpaque = false
    }

    fun configure(title: String) {
        this.title = title
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(400, 28)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        // Background
        g2.color = JBColor(Color(240, 240, 240), Color(50, 50, 55))
        g2.fillRect(0, 0, width, height)

        // Separator line at bottom
        g2.color = JBColor(Color(220, 220, 220), Color(60, 60, 60))
        g2.fillRect(0, height - 1, width, 1)

        // Title text
        val baseFont = font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
        g2.font = baseFont.deriveFont(Font.BOLD)
        g2.color = JBColor(Color(80, 80, 80), Color(180, 180, 180))
        val fm = g2.fontMetrics
        g2.drawString(title, 10, (height + fm.ascent - fm.descent) / 2)

        g2.dispose()
    }
}

private class CommentCellPanel : JPanel() {

    private var comment: ReviewComment? = null
    private var isSelectedState = false
    private var listRef: JList<*>? = null

    private var showDelete = false
    private var deleteHovered = false

    private var deleteBtnBounds: Rectangle? = null
    var badgeBounds: Rectangle? = null
        private set

    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 0, 1, 0) // 1px separator
    }

    fun configure(comment: ReviewComment, isSelected: Boolean, list: JList<*>, showDelete: Boolean, deleteHovered: Boolean) {
        this.comment = comment
        this.isSelectedState = isSelected
        this.listRef = list
        this.showDelete = showDelete
        this.deleteHovered = deleteHovered
    }

    fun isDeleteHit(x: Int, y: Int): Boolean {
        val bounds = deleteBtnBounds ?: return false
        return bounds.contains(x, y)
    }

    fun isBadgeHit(x: Int, y: Int): Boolean {
        val bounds = badgeBounds ?: return false
        return bounds.contains(x, y)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12))
        // Badge line height + comment text line + padding
        val lineHeight = fm.height
        val totalHeight = PADDING_TOP + BADGE_HEIGHT + GAP + lineHeight + PADDING_BOTTOM
        return Dimension(400, totalHeight)
    }

    public override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val comment = this.comment ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val w = width
        val h = height
        val baseFont = font ?: UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)

        // Background
        if (isSelectedState) {
            g2.color = listRef?.selectionBackground ?: UIManager.getColor("List.selectionBackground")
        } else {
            g2.color = comment.type.listBackground
        }
        g2.fillRect(0, 0, w, h)

        // Accent bar
        g2.color = comment.type.accentColor
        g2.fillRect(0, 0, ACCENT_WIDTH, h)

        // Separator line at bottom
        g2.color = JBColor(Color(220, 220, 220), Color(60, 60, 60))
        g2.fillRect(0, h - 1, w, 1)

        val contentX = ACCENT_WIDTH + PADDING_LEFT
        var currentY = PADDING_TOP

        // --- First row: Type badge + location + delete icon ---
        val badgeFont = baseFont.deriveFont(Font.BOLD, baseFont.size2D - 1f)
        g2.font = badgeFont
        val badgeFm = g2.fontMetrics
        val badgeText = comment.type.label
        val badgeTextW = badgeFm.stringWidth(badgeText)
        val badgeW = badgeTextW + BADGE_PAD_H * 2

        // Draw badge pill
        g2.color = comment.type.accentColor
        g2.fill(RoundRectangle2D.Float(
            contentX.toFloat(), currentY.toFloat(),
            badgeW.toFloat(), BADGE_HEIGHT.toFloat(), 6f, 6f
        ))
        g2.color = Color.WHITE
        val badgeTextY = currentY + badgeFm.ascent + (BADGE_HEIGHT - badgeFm.height) / 2
        g2.drawString(badgeText, contentX + BADGE_PAD_H, badgeTextY)
        badgeBounds = Rectangle(contentX, currentY, badgeW, BADGE_HEIGHT)

        // Location text
        val locationFont = baseFont.deriveFont(Font.PLAIN, baseFont.size2D - 1f)
        g2.font = locationFont
        val locFm = g2.fontMetrics
        val locationText = "${formatLocation(comment)} · ${DateFormatUtil.formatBetweenDates(comment.createdAt, System.currentTimeMillis())}"
        if (isSelectedState) {
            g2.color = listRef?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
        } else {
            g2.color = JBColor(Color(120, 120, 120), Color(160, 160, 160))
        }
        val locX = contentX + badgeW + 8
        val locY = currentY + locFm.ascent + (BADGE_HEIGHT - locFm.height) / 2
        g2.drawString(locationText, locX, locY)

        // Delete button (right side), styled like the editor inlay's
        deleteBtnBounds = null
        if (showDelete) {
            val deleteIcon = AllIcons.General.Remove
            val delBtnW = deleteIcon.iconWidth + ICON_BTN_PAD * 2
            val delX = w - PADDING_RIGHT - delBtnW
            if (deleteHovered) {
                g2.color = JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 30))
                g2.fillRoundRect(delX, currentY, delBtnW, BADGE_HEIGHT, 4, 4)
            }
            deleteIcon.paintIcon(this, g2, delX + ICON_BTN_PAD, currentY + (BADGE_HEIGHT - deleteIcon.iconHeight) / 2)
            deleteBtnBounds = Rectangle(delX, currentY, delBtnW, BADGE_HEIGHT)
        }

        currentY += BADGE_HEIGHT + GAP

        // --- Second row: Comment text (truncated to single line) ---
        g2.font = baseFont
        val textFm = g2.fontMetrics
        val maxTextW = w - contentX - PADDING_RIGHT
        val displayText = truncateText(comment.text.replace("\n", " "), textFm, maxTextW)

        if (isSelectedState) {
            g2.color = listRef?.selectionForeground ?: UIManager.getColor("List.selectionForeground")
        } else {
            g2.color = JBColor(Color(50, 50, 50), Color(210, 210, 210))
        }
        g2.drawString(displayText, contentX, currentY + textFm.ascent)

        g2.dispose()
    }

    companion object {
        private const val ACCENT_WIDTH = 3
        private const val PADDING_LEFT = 10
        private const val PADDING_RIGHT = 10
        private const val PADDING_TOP = 8
        private const val PADDING_BOTTOM = 8
        private const val BADGE_HEIGHT = 18
        private const val BADGE_PAD_H = 6
        private const val GAP = 4
        private const val ICON_BTN_PAD = 3

        private fun formatLocation(comment: ReviewComment): String = when (comment.scope) {
            CommentScope.LINE -> {
                val range = if (comment.lineEnd != null && comment.lineEnd != comment.lineStart) {
                    "L${comment.lineStart}-${comment.lineEnd}"
                } else {
                    "L${comment.lineStart}"
                }
                listOfNotNull(
                    range,
                    "code changed".takeIf { comment.outdated },
                    "resolved".takeIf { comment.resolved },
                    "${comment.replies.size} repl${if (comment.replies.size == 1) "y" else "ies"}".takeIf { comment.replies.isNotEmpty() }
                ).joinToString(" · ")
            }
            CommentScope.FILE -> "Whole file"
            CommentScope.REVIEW -> "Review"
        }

        private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
            if (text.isEmpty()) return ""
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "\u2026"
            val ellipsisW = fm.stringWidth(ellipsis)
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) {
                end--
            }
            return text.substring(0, end.coerceAtLeast(1)) + ellipsis
        }
    }
}
