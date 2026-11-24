package com.codereview.editor

import com.codereview.model.CommentType
import com.codereview.ui.accentColor
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*

class InlineCommentEditorPanel(
    initialType: CommentType = CommentType.MUST_DO,
    initialText: String = "",
    private val onSave: (CommentType, String) -> Unit,
    private val onCancel: () -> Unit
) : JPanel(BorderLayout(0, 4)) {

    private val typeCombo = ComboBox(CommentType.entries.toTypedArray()).apply {
        selectedItem = initialType
    }
    val textArea: JTextArea = JTextArea(initialText, 4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("EditorPane.font") ?: font
    }

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

        val scrollPane = JBScrollPane(textArea).apply {
            preferredSize = Dimension(0, 80)
        }
        add(scrollPane, BorderLayout.CENTER)

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

        installTypeShortcuts(typeCombo, textArea)

        // Escape to cancel
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke("ESCAPE"), "cancel-edit"
        )
        textArea.actionMap.put("cancel-edit", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { cancel() }
        })

        // Ctrl+Enter also saves on macOS, where users used it before Cmd+Enter existed
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(SAVE_KEYSTROKE, "save-edit")
        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke("ctrl ENTER"), "save-edit"
        )
        textArea.actionMap.put("save-edit", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { save() }
        })
    }

    private fun save() {
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            onSave(typeCombo.selectedItem as CommentType, text)
        }
    }

    private fun cancel() {
        onCancel()
    }

    companion object {
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
            "${KeymapUtil.getKeystrokeText(typeKeyStroke(index))} ${type.name}"
        }.joinToString(", ")
    }
}
