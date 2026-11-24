package com.codereview.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

@Service(Service.Level.APP)
@State(name = "CodeReviewAnnotatorSettings", storages = [Storage("codeReviewAnnotator.xml")])
class CodeReviewSettings : PersistentStateComponent<CodeReviewSettings.State> {

    class State {
        var submitCommand: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): CodeReviewSettings = service()
    }
}

class CodeReviewConfigurable : BoundConfigurable("Code Review Annotator") {

    override fun createPanel(): DialogPanel = panel {
        row("Submit command:") {
            textField()
                .bindText(CodeReviewSettings.getInstance().state::submitCommand)
                .columns(COLUMNS_LARGE)
                .comment(
                    "Runs in a new terminal tab. {file} is replaced with the path of a file holding the review " +
                        "markdown, for example: claude \"$(cat {file})\". " +
                        "Leave empty to paste the review into the active terminal tab."
                )
        }
    }
}
