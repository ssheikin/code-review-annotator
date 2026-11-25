package com.codereview.actions

import com.intellij.ide.actions.ActivateToolWindowAction

// plugin.xml can only give a shortcut to an action it declares. The IDE registers its own
// activate action for a tool window only when that ID is still free, so this one takes its place.
class ActivateReviewToolWindowAction : ActivateToolWindowAction("Code Review")
