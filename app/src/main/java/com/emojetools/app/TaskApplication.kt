package com.emojetools.app

import android.app.Application
import com.emojetools.app.core.task.TaskCoordinator

open class TaskApplication : Application() {
    val tasks: TaskCoordinator by lazy { createCoordinator() }
    protected open fun createCoordinator() = TaskCoordinator(this)
}
