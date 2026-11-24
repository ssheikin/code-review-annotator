package com.codereview.actions

import com.codereview.editor.InlineCommentManager
import com.codereview.service.ReviewSessionService
import com.codereview.toolwindow.ReviewToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element

class ExportReviewsToFileAction : AnAction(
    "Export Reviews to File...",
    "Save the current review and the review history to a file",
    AllIcons.ToolbarDecorator.Export
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Export Reviews", "", "xml"), project)
            .save("code-reviews.xml") ?: return
        val state = ReviewSessionService.getInstance(project).state
        val element = XmlSerializer.serialize(state) ?: Element("State")
        JDOMUtil.write(element, target.file.toPath())
        notify(project, "Exported reviews to ${target.file.path}.")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ImportReviewsFromFileAction : AnAction(
    "Import Reviews from File...",
    "Load a review and its history from an exported file",
    AllIcons.ToolbarDecorator.Import
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("xml")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        try {
            val state = XmlSerializer.deserialize(JDOMUtil.load(file.toNioPath()), ReviewSessionService.State::class.java)
            ReviewSessionService.getInstance(project).importState(state)
        } catch (ex: Exception) {
            notify(project, "Could not read ${file.path}: ${ex.message}", NotificationType.ERROR)
            return
        }
        InlineCommentManager.resyncAll(project)
        ReviewToolWindowFactory.refreshPanel(project)
        notify(project, "Imported reviews from ${file.path}.")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

private fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("CodeReviewAnnotator")
        .createNotification(message, type)
        .notify(project)
}
