package com.emojetools.app.core.task

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.emojetools.app.R
import com.emojetools.app.TaskApplication
import com.emojetools.app.core.ErrorCode
import com.emojetools.app.ui.*
import kotlinx.coroutines.*

class TaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tasks get() = (application as TaskApplication).tasks
    private var job: Job? = null
    private var startId = 0
    private var taskId = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var alive = true
    private var pendingRun: Intent? = null
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.task_notifications), NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            tasks.state.collect { state ->
                if (state.activeId == taskId) state.tasks.firstOrNull { it.id == taskId }?.let {
                    notifyAllowed(notification(it, ongoing = true))
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        this.startId = startId
        if (intent?.action == CANCEL) {
            val requested = intent.getLongExtra(TASK_ID, 0)
            if (requested == taskId && tasks.state.value.activeId != requested) job?.cancel()
            else tasks.cancel(requested)
            if (job?.isCompleted != false) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != RUN) { stopSelf(startId); return START_NOT_STICKY }
        if (job?.isCompleted == false) {
            if (!tasks.busy) pendingRun = Intent(intent)
            return START_NOT_STICKY
        }
        taskId = intent.getLongExtra(TASK_ID, 0)
        android.util.Log.i("EmojeTasks", "service_start task=$taskId")
        startForeground(NOTIFICATION_ID, notification(tasks.task(taskId), ongoing = true))
        job = scope.launch {
            val executingId = taskId
            try {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "emojetools:task").apply {
                    acquire(6 * 60 * 60 * 1000L)
                }
                tasks.run(taskId)
            } catch (_: CancellationException) { }
            catch (e: Exception) { android.util.Log.e("EmojeTasks", "service_failed type=${e.javaClass.simpleName}") }
            finally {
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                if (alive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    tasks.task(taskId)?.let { notifyAllowed(notification(it, ongoing = false)) }
                    val next = pendingRun; pendingRun = null
                    if (next == null) stopSelf(this@TaskService.startId)
                    else { job = null; onStartCommand(next, 0, this@TaskService.startId) }
                }
                android.util.Log.i("EmojeTasks", "service_end task=$executingId state=${tasks.task(executingId)?.stage} wake_lock_released=true")
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        tasks.cancel(taskId, asInterrupted = true)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        alive = false
        tasks.cancel(taskId, asInterrupted = true)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(task: UiTask?, ongoing: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).putExtra(TASK_ID, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = when {
            ongoing -> task?.detail?.ifBlank { getString(R.string.preparing) } ?: getString(R.string.preparing)
            task?.errorCode == ErrorCode.NEED_LOGIN.name -> getString(R.string.login_required)
            task?.stage == TaskStage.SELECTING -> getString(R.string.task_selecting)
            task?.stage == TaskStage.DONE -> task.detail
            task?.stage == TaskStage.CANCELLED -> getString(R.string.task_cancelled)
            task?.stage == TaskStage.INTERRUPTED -> getString(R.string.task_recovered)
            else -> task?.detail.orEmpty()
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
            .setContentTitle(task?.title?.ifBlank { task.postId.ifBlank { getString(R.string.app_name) } } ?: getString(R.string.app_name))
            .setContentText(status).setStyle(NotificationCompat.BigTextStyle().bigText(status)).setContentIntent(content)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply {
                if (ongoing) {
                    setProgress(0, 0, true)
                    val cancel = PendingIntent.getService(this@TaskService, 2, Intent(this@TaskService, TaskService::class.java)
                        .setAction(CANCEL).putExtra(TASK_ID, taskId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(R.drawable.ic_x, getString(R.string.cancel), cancel)
                } else if (task?.errorCode == ErrorCode.NEED_LOGIN.name) {
                    val login = PendingIntent.getActivity(this@TaskService, 3, Intent(open).putExtra(LOGIN_TASK, taskId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(R.drawable.ic_rotate_ccw, getString(R.string.login_and_resume), login)
                } else if (task?.stage in listOf(TaskStage.FAILED, TaskStage.PARTIAL, TaskStage.CANCELLED, TaskStage.INTERRUPTED)) {
                    val retry = PendingIntent.getForegroundService(this@TaskService, 4, Intent(this@TaskService, TaskService::class.java)
                        .setAction(RUN).putExtra(TASK_ID, taskId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(R.drawable.ic_rotate_ccw, getString(R.string.resume_task), retry)
                }
            }.build()
    }
    private fun notifyAllowed(notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            notifications.notify(NOTIFICATION_ID, notification)
    }
    companion object {
        const val TASK_ID = "task_id"
        const val LOGIN_TASK = "login_task"
        const val RUN = "com.emojetools.app.RUN_TASK"
        const val CANCEL = "com.emojetools.app.CANCEL_TASK"
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 41
        fun start(context: Context, id: Long) {
            ContextCompat.startForegroundService(context, Intent(context, TaskService::class.java).setAction(RUN).putExtra(TASK_ID, id))
        }
    }
}
