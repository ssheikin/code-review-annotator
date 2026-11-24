package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.ui.accentColor
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
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
import com.intellij.ui.SimpleListCellRenderer
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

    private val typeCombo = ComboBox(CommentType.entries.toTypedArray()).apply {
        selectedItem = initialType
        renderer = SimpleListCellRenderer.create("") { it.label }
    }

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

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 3, 0, 0, initialType.accentColor),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )
        background = JBColor(Color(245, 245, 250), Color(45, 45, 50))

        // Top bar: type combo + buttons
        val topBar = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            val typePanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel("Type:"))
                add(typeCombo)
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

        // Update accent bar color on type change
        typeCombo.addActionListener {
            val type = typeCombo.selectedItem as CommentType
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, type.accentColor),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
            )
        }

        installTypeShortcuts(typeCombo, textField)

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

    private fun save() {
        val text = textField.text.trim()
        if (text.isNotEmpty()) {
            onSave(typeCombo.selectedItem as CommentType, text)
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

        // Registered as IDE actions rather than Swing key bindings so the
        // focused component wins over global keymap entries like Alt+1.
        fun installTypeShortcuts(combo: ComboBox<CommentType>, target: JComponent) {
            CommentType.entries.forEachIndexed { index, type ->
                DumbAwareAction.create { combo.selectedItem = type }
                    .registerCustomShortcutSet(CustomShortcutSet(typeKeyStroke(index)), target)
            }
            combo.toolTipText = typeShortcutHint()
        }

        fun typeShortcutHint(): String = CommentType.entries.mapIndexed { index, type ->
            "${KeymapUtil.getKeystrokeText(typeKeyStroke(index))} ${type.label}"
        }.joinToString(", ")
    }
}
