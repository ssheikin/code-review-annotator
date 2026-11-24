package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.ui.accentColor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*

class InlineCommentEditorPanel(
    project: Project,
    initialType: CommentType = CommentType.MUST_DO,
    initialText: String = "",
    private val onSave: (CommentType, String) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout(0, 4)) {

    private var selectedType = initialType

    private val typeButtons = CommentType.entries.associateWith { TypeToggleButton(it) }

    // A nested editor rather than a JTextArea: inside the host editor, a plain text area
    // would lose keys like Backspace and Enter to the host editor's actions.
    val textField: EditorTextField = EditorTextField(
        EditorFactory.getInstance().createDocument(initialText), project, FileTypes.PLAIN_TEXT, false, false
    ).apply {
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            editor.setVerticalScrollbarVisible(false)
            editor.contentComponent.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
    }

    var fixedWidth: Int = 600

    var onContentResized: () -> Unit = {}

    // Lets an existing comment take the new type at once, so the list shows it while the text is edited.
    var onTypeChanged: (CommentType) -> Unit = {}

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, initialType.accentColor),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )
        background = JBColor(Color(245, 245, 250), Color(45, 45, 50))

        val typeGroup = ButtonGroup()
        typeButtons.forEach { (type, button) ->
            typeGroup.add(button)
            button.addActionListener { pickType(type) }
        }
        typeButtons.getValue(initialType).isSelected = true

        // Top bar: type buttons + buttons
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            val typePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                typeButtons.values.forEach { add(it) }
            }
            add(typePanel, BorderLayout.WEST)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                val saveBtn = JButton("Save").apply {
                    addActionListener { save() }
                }
                val cancelBtn = JButton("Cancel").apply {
                    addActionListener { cancel() }
                }
                add(saveBtn)
                add(cancelBtn)
            }
            add(buttonPanel, BorderLayout.EAST)
        }
        add(topBar, BorderLayout.NORTH)
        add(textField, BorderLayout.CENTER)

        val hintLabel = JLabel("${typeShortcutHint()}, ${KeymapUtil.getKeystrokeText(SAVE_KEYSTROKE)} to save, Escape to cancel").apply {
            foreground = JBColor(Color(140, 140, 140), Color(120, 120, 120))
            font = font.deriveFont(font.size2D - 1)
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }
        add(hintLabel, BorderLayout.SOUTH)

        installTypeShortcuts(textField) { pickType(it) }

        // IDE actions, so they win over the nested editor's own Escape and Enter handling.
        // Ctrl+Enter also saves on macOS, where users used it before Cmd+Enter existed.
        DumbAwareAction.create { cancel() }
            .registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke("ESCAPE")), textField)
        DumbAwareAction.create { save() }
            .registerCustomShortcutSet(
                CustomShortcutSet(KeyboardShortcut(SAVE_KEYSTROKE, null), KeyboardShortcut(KeyStroke.getKeyStroke("ctrl ENTER"), null)), textField
            )

        textField.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                SwingUtilities.invokeLater { onContentResized() }
            }
        })
    }

    override fun getPreferredSize(): Dimension {
        val minTextHeight = textField.getFontMetrics(textField.font).height * MIN_LINES
        val textHeight = maxOf(textField.preferredSize.height, minTextHeight)
        val chrome = super.getPreferredSize().height - textField.preferredSize.height
        return Dimension(fixedWidth, chrome + textHeight)
    }

    private fun pickType(type: CommentType) {
        if (type == selectedType) return
        selectType(type)
        onTypeChanged(type)
    }

    fun selectType(type: CommentType) {
        selectedType = type
        typeButtons.getValue(type).isSelected = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, type.accentColor),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )
    }

    private fun save() {
        val text = textField.text.trim()
        if (text.isNotEmpty()) {
            onSave(selectedType, text)
        }
    }

    private fun cancel() {
        onCancel()
    }

    companion object {
        private const val MIN_LINES = 3

        private val SAVE_KEYSTROKE: KeyStroke =
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK)

        private fun typeKeyStroke(index: Int): KeyStroke =
            KeyStroke.getKeyStroke(KeyEvent.VK_1 + index, InputEvent.ALT_DOWN_MASK)

        fun typeShortcutText(type: CommentType): String = KeymapUtil.getKeystrokeText(typeKeyStroke(type.ordinal))

        // Registered as IDE actions rather than Swing key bindings so the
        // focused component wins over global keymap entries like Alt+1.
        // Returned so a context menu can reuse them and show their shortcuts.
        fun installTypeShortcuts(
            target: JComponent,
            isEnabled: () -> Boolean = { true },
            isSelected: (CommentType) -> Boolean = { false },
            select: (CommentType) -> Unit
        ): List<AnAction> = CommentType.entries.mapIndexed { index, type ->
            object : DumbAwareAction(type.label), Toggleable {
                override fun actionPerformed(e: AnActionEvent) = select(type)

                // When disabled, the key falls through to the keymap, such as Alt+1 for the Project view.
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = isEnabled()
                    Toggleable.setSelected(e.presentation, isSelected(type))
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }.apply { registerCustomShortcutSet(CustomShortcutSet(typeKeyStroke(index)), target) }
        }

        fun installTypeShortcuts(combo: ComboBox<CommentType>, target: JComponent) {
            installTypeShortcuts(target) { combo.selectedItem = it }
            combo.toolTipText = typeShortcutHint()
        }

        fun typeShortcutHint(): String = CommentType.entries.mapIndexed { index, type ->
            "${KeymapUtil.getKeystrokeText(typeKeyStroke(index))} ${type.label}"
        }.joinToString(", ")
    }
}

// Painted by hand because the platform button UIs ignore a custom background colour.
private class TypeToggleButton(val type: CommentType) : JToggleButton(type.label) {

    init {
        // Clicking must leave focus in the text field so typing continues.
        isFocusable = false
        isRequestFocusEnabled = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        isRolloverEnabled = true
        toolTipText = "${type.label} (${InlineCommentEditorPanel.typeShortcutText(type)})"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    // Sized for the bold font so the row does not shift when the selection moves.
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font.deriveFont(Font.BOLD))
        return Dimension(fm.stringWidth(text) + PAD_H * 2, fm.height + PAD_V * 2)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        if (isSelected) {
            g2.color = type.accentColor
            g2.fillRoundRect(0, 0, width, height, ARC, ARC)
            g2.font = font.deriveFont(Font.BOLD)
            g2.color = Color.WHITE
        } else {
            g2.color = if (model.isRollover) type.accentColor else JBColor(Color(190, 190, 190), Color(90, 90, 90))
            g2.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
            g2.font = font
            g2.color = foreground
        }
        val fm = g2.fontMetrics
        g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }

    companion object {
        private const val PAD_H = 8
        private const val PAD_V = 2
        private const val ARC = 6
    }
}
