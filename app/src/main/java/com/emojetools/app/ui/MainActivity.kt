package com.emojetools.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import com.emojetools.app.BuildConfig
import com.emojetools.app.R
import com.emojetools.app.TaskApplication
import com.emojetools.app.core.*
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.ApiResponse
import com.emojetools.app.core.store.StoredAsset
import com.emojetools.app.core.store.MediaSharing
import com.emojetools.app.core.task.TaskService
import com.emojetools.app.platform.douyin.CommentFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val coordinator get() = (application as TaskApplication).tasks
    private val store get() = coordinator.store
    private val sessions get() = coordinator.sessions
    private val config get() = coordinator.config
    private val mapper get() = coordinator.mapper
    private lateinit var screen: MainScreen
    private val tasks = mutableListOf<UiTask>()
    private var ready = false
    private var resumed = false
    private var running: Job? = null
    private var selectionDialog: Dialog? = null
    private var pendingOpenId: Long? = null
    private var pendingLoginId: Long? = null
    private var resumeAfterLogin: Long? = null
    private var pendingResumeId: Long? = null
    private var pendingQualityId: Long? = null
    private var refreshQualityAfterLogin = false
    private var pendingStorageTask: Long? = null
    private var pendingStorageAsset: String? = null
    private val previewLimit = Semaphore(3)
    private var previews = mutableMapOf<String, Deferred<File>>()
    private var remotePreviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var previewDirectory: File
    private val coverBackfills = mutableSetOf<String>()
    private val taskThumbnails = mutableMapOf<String, Deferred<File?>>()
    private var commentSelection: CommentSelection? = null
    private var commentTaskId: Long? = null
    private var commentQueryDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewDirectory = File(cacheDir, "previews/${UUID.randomUUID()}")
        screen = MainScreen(this, ::submit, { openLogin() }, ::settings, ::showTask, ::cancelTask, ::retryTask, ::showAsset, ::previewStored,
            ::previewTask, ::deleteTask, { startActivity(Intent(this, FavoriteStickersActivity::class.java)) },
            { startActivity(Intent(this, AccountWorksActivity::class.java).putExtra(AccountWorksActivity.PLATFORM,
                if (screen.mode == DownloadMode.BILIBILI) "bilibili" else "douyin")) })
        setContentView(screen.root)
        screen.sourceInput.setText(savedInstanceState?.getString("source") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        screen.targetInput.setText(savedInstanceState?.getString("target").orEmpty())
        pendingStorageTask = savedInstanceState?.getLong("storage_task", 0)?.takeIf { it != 0L }
        pendingStorageAsset = savedInstanceState?.getString("storage_asset")
        pendingOpenId = savedInstanceState?.getLong("pending_open", 0)?.takeIf { it != 0L }
            ?: intent.getLongExtra(TaskService.TASK_ID, 0).takeIf { it != 0L }
        pendingLoginId = intent.getLongExtra(TaskService.LOGIN_TASK, 0).takeIf { it != 0L }
        intent.removeExtra(TaskService.LOGIN_TASK); intent.removeExtra(TaskService.TASK_ID)
        resumeAfterLogin = savedInstanceState?.getLong("resume_login", 0)?.takeIf { it != 0L }
        pendingResumeId = savedInstanceState?.getLong("pending_resume", 0)?.takeIf { it != 0L }
        pendingQualityId = savedInstanceState?.getLong("pending_quality", 0)?.takeIf { it != 0L }
        refreshQualityAfterLogin = savedInstanceState?.getBoolean("quality_after_login", false) ?: false
        savedInstanceState?.let { screen.restoreSelection(it.getString("mode"), it.getInt("target_kind"), it.getInt("tab"),
            CommentOrder.valueOf(it.getString("comment_order", CommentOrder.TOP_FIRST.name))) }
        scope.launch {
            try {
                coordinator.initialize(); ready = true; screen.setReady(true); refreshAccount()
                coordinator.state.collect { state ->
                    tasks.clear(); tasks.addAll(state.tasks)
                    val ids = state.tasks.map { it.id.toString() }.toSet()
                    taskThumbnails.entries.removeAll { (key, job) ->
                        if (key.substringBefore(':') !in ids) { job.cancel(); true } else false
                    }
                    screen.renderTasks(tasks)
                    screen.setWorking(state.activeId != null || running?.isActive == true)
                    if (state.activeId == null) refreshLibrary()
                    commentTaskId?.let { id ->
                        val searching = state.activeId == id
                        coordinator.liveResult(id)?.let { result ->
                            commentSelection?.update(result, searching)
                        }
                        val resumable = !searching && state.tasks.firstOrNull { it.id == id }?.let { coordinator.canContinueCommentSearch(it) } == true
                        if (commentTaskId == id && (coordinator.state.value.activeId == id) == searching)
                            commentSelection?.updateSearchState(searching, resumable)
                    }
                    if (selectionDialog?.isShowing != true && pendingOpenId == state.activeId) {
                        val liveTask = state.tasks.firstOrNull { it.id == state.activeId && it.mode == DownloadMode.COMMENTS && it.stage == TaskStage.FETCHING }
                        val partial = liveTask?.let { coordinator.liveResult(it.id) }
                        if (liveTask != null && partial != null && partial.fetchedTopLevel > 0) {
                            pendingOpenId = null; showComments(liveTask, partial, true)
                        }
                    }
                    openPending()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        pendingOpenId = intent.getLongExtra(TaskService.TASK_ID, 0).takeIf { it != 0L }
        pendingLoginId = intent.getLongExtra(TaskService.LOGIN_TASK, 0).takeIf { it != 0L }
        intent.removeExtra(TaskService.LOGIN_TASK); intent.removeExtra(TaskService.TASK_ID)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { screen.sourceInput.setText(it); screen.selectTab(0) }
        openPending()
    }
    private fun openPending() {
        if (!ready || !resumed || coordinator.busy || running?.isActive == true) return
        pendingQualityId?.let { id ->
            pendingQualityId = null
            utility { coordinator.refreshBilibili(id); coordinator.task(id)?.let { chooseContent(it) } }
            return
        }
        pendingStorageAsset?.takeIf { !needsStoragePermission() }?.let { key ->
            pendingStorageAsset = null
            store.all().firstOrNull { it.key == key }?.let(::saveExistingAsset)
            return
        }
        pendingResumeId?.let { id ->
            pendingResumeId = null
            pendingOpenId = id.takeIf { coordinator.task(id)?.let { it.operation == "discover" || it.outputAction == OutputAction.SHARE || it.mode == DownloadMode.COMMENTS } == true }
            screen.selectTab(1)
            utility { startTask(id) }; return
        }
        pendingLoginId?.let { id -> pendingLoginId = null; pendingOpenId = null; openLogin(id); return }
        val id = pendingOpenId ?: return
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (task.stage == TaskStage.INTERRUPTED && task.detail.isBlank()) return
        pendingOpenId = null
        screen.selectTab(1)
        if (task.stage == TaskStage.DONE && task.outputAction == OutputAction.SAVE && task.outputs.isNotEmpty()) toast(task.detail)
        else showTask(task)
    }
    private fun submit() = utility {
        val source = screen.sourceInput.text.toString().trim()
        val mode = coordinator.inputMode(source, screen.mode)
        screen.selectMode(mode)
        val id = coordinator.add(UiTask(System.currentTimeMillis(), source, mode,
            screen.outputFormat, screen.targetInput.text.toString().trim(), targetKind = screen.targetKind, commentOrder = screen.commentOrder))
        pendingOpenId = id; screen.selectTab(1); startTask(id)
    }
    private fun startTask(id: Long) {
        val task = coordinator.task(id)
        if (task?.operation == "download" && task.outputAction == OutputAction.SAVE && needsStoragePermission()) {
            pendingStorageTask = id
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
            return
        }
        requestNotifications()
        TaskService.start(this, id)
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("ui", MODE_PRIVATE)
            if (!prefs.getBoolean("notifications_asked", false)) {
                prefs.edit().putBoolean("notifications_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 302)
            }
        }
    }
    private fun cancelTask(task: UiTask) { coordinator.cancel(task.id) }
    private fun deleteTask(task: UiTask) {
        AlertDialog.Builder(this).setTitle(R.string.delete_task)
            .setMessage(if (coordinator.state.value.activeId == task.id) R.string.delete_running_task_confirm else R.string.delete_task_confirm)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.delete_task) { _, _ ->
                scope.launch {
                    try {
                        if (pendingOpenId == task.id) pendingOpenId = null
                        if (pendingResumeId == task.id) pendingResumeId = null
                        if (pendingLoginId == task.id) pendingLoginId = null
                        if (resumeAfterLogin == task.id) resumeAfterLogin = null
                        if (pendingStorageTask == task.id) pendingStorageTask = null
                        coordinator.delete(task.id)
                        if (!coordinator.busy) getSystemService(android.app.NotificationManager::class.java).cancel(TaskService.NOTIFICATION_ID)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { reportError(e) }
                }
            }.show()
    }
    private fun retryTask(task: UiTask) {
        if (task.mode == DownloadMode.ACCOUNT_WORKS && task.errorCode == ErrorCode.UNSUPPORTED.name) {
            startActivity(Intent(this, AccountWorksActivity::class.java).putExtra(AccountWorksActivity.KIND, task.source)
                .putExtra(AccountWorksActivity.PLATFORM, coordinator.taskPlatform(task))); return
        }
        if (task.mode == DownloadMode.FAVORITES && task.errorCode == ErrorCode.UNSUPPORTED.name) {
            startActivity(Intent(this, FavoriteStickersActivity::class.java)); return
        }
        if (task.errorCode == ErrorCode.NEED_LOGIN.name) { openLogin(task.id); return }
        utility {
            if (task.operation == "discover" || task.outputAction == OutputAction.SHARE || task.mode == DownloadMode.COMMENTS) pendingOpenId = task.id
            startTask(task.id)
        }
    }
    private fun showTask(task: UiTask) {
        if (task.outputAction == OutputAction.SHARE && task.outputs.isNotEmpty() && task.stage in listOf(TaskStage.DONE, TaskStage.PARTIAL)) {
            if (task.stage == TaskStage.DONE) utility { shareTask(task) }
            else AlertDialog.Builder(this).setTitle(R.string.share_asset).setMessage(task.detail)
                .setPositiveButton(R.string.share_asset) { _, _ -> utility { shareTask(task) } }
                .setNeutralButton(task.retryLabel()) { _, _ -> retryTask(task) }.setNegativeButton(R.string.close, null).show()
            return
        }
        if (task.mode == DownloadMode.ACCOUNT_WORKS && task.stage == TaskStage.DONE) {
            startActivity(Intent(this, AccountWorksActivity::class.java).putExtra(AccountWorksActivity.KIND, task.source)
                .putExtra(AccountWorksActivity.PLATFORM, coordinator.taskPlatform(task))); return
        }
        if (task.mode == DownloadMode.FAVORITES && task.stage == TaskStage.DONE) {
            startActivity(Intent(this, FavoriteStickersActivity::class.java)); return
        }
        if (coordinator.state.value.activeId == task.id && task.mode == DownloadMode.COMMENTS) {
            coordinator.liveResult(task.id)?.let { showComments(task, it, true); return }
        }
        if (!ready || coordinator.busy || running?.isActive == true) { showTaskDetails(task); return }
        if (task.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED, TaskStage.PARTIAL, TaskStage.INTERRUPTED)) showTaskDetails(task)
        else utility { chooseContent(task) }
    }
    private suspend fun chooseContent(task: UiTask) {
        if (task.mode == DownloadMode.ACCOUNT_WORKS) { showTaskDetails(task); return }
        if (task.mode == DownloadMode.FAVORITES) { showTaskDetails(task); return }
        selectionDialog?.dismiss()
        if (task.mode == DownloadMode.COMMENTS) {
            val result = coordinator.loadResult(task) ?: run { showTaskDetails(task); return }
            showComments(task, result, false)
        } else {
            val media = coordinator.loadPost(task) ?: run { showTaskDetails(task); return }
            val selectPart: ((String) -> Unit)? = if (task.mode == DownloadMode.BILIBILI) { cid ->
                utility {
                    coordinator.selectBilibiliPart(task.id, cid)
                    chooseContent(coordinator.task(task.id) ?: task)
                }
            } else null
            val login: (() -> Unit)? = if (task.mode == DownloadMode.BILIBILI) { { openLogin(task.id, true) } } else null
            selectionDialog = PostSelection(this, task, media, ::previewRemote, selectPart, login) { selected, action -> saveSelection(task, selected, action) }.show().apply {
                setOnDismissListener { clearRemotePreviews() }
            }
        }
    }
    private fun showComments(task: UiTask, result: CommentResult, searching: Boolean) {
        selectionDialog?.dismiss()
        var queryTask = task
        lateinit var controller: CommentSelection
        controller = CommentSelection(this, task, result, ::previewRemote,
            onContinue = { selected -> continueComments(queryTask, selected) }, onStop = { coordinator.cancel(task.id, asInterrupted = true) },
            searching = searching, loadPreview = ::remotePreviewFile, onSearch = {
                searchComments(queryTask, controller.selectionIds()) { updated -> queryTask = updated; controller.retarget(updated) }
            }) { selected, action -> saveSelection(queryTask, selected, action) }
        commentSelection = controller; commentTaskId = task.id
        selectionDialog = controller.show().apply {
            setOnDismissListener {
                controller.closePreview()
                if (commentSelection === controller) { commentSelection = null; commentTaskId = null }
                clearRemotePreviews()
            }
        }
        if (!searching) scope.launch {
            try {
                val resumable = coordinator.canContinueCommentSearch(task)
                if (commentSelection === controller && coordinator.state.value.activeId != task.id)
                    controller.updateSearchState(false, resumable)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
        }
    }
    private fun searchComments(task: UiTask, selected: Set<String>, onChanged: (UiTask) -> Unit) {
        val kinds = listOf(R.string.target_text, R.string.target_author_name, R.string.target_mine)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, kinds.map { getString(it) })
            setSelection(kinds.indexOf(task.targetKind).coerceAtLeast(0))
        }
        val query = EditText(this).apply { setText(task.target); setHint(R.string.target_author_name_hint); isSingleLine = true; textSize = 14f }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                query.visibility = if (kinds[position] == R.string.target_mine) View.GONE else View.VISIBLE
                query.setHint(if (kinds[position] == R.string.target_text) R.string.target_text_hint else R.string.target_author_name_hint)
            }
        }
        form.addView(type, LinearLayout.LayoutParams(-1, (48 * resources.displayMetrics.density).toInt()))
        form.addView(query, LinearLayout.LayoutParams(-1, (52 * resources.displayMetrics.density).toInt()))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.search_cached_comments).setView(form)
            .setNegativeButton(R.string.close, null).setPositiveButton(R.string.search_in_cache, null).create()
        commentQueryDialog?.dismiss(); commentQueryDialog = dialog
        dialog.setOnDismissListener { if (commentQueryDialog === dialog) commentQueryDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kind = kinds[type.selectedItemPosition]
                if (kind != R.string.target_mine && query.text.isNullOrBlank()) { query.error = getString(R.string.cached_query_empty); return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                scope.launch {
                    try {
                        val updated = coordinator.changeCommentQuery(task.id, kind, query.text.toString(), selected)
                        onChanged(updated); dialog.dismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { reportError(e); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.show()
    }
    private fun continueComments(task: UiTask, selected: Set<String>) {
        utility {
            val updated = coordinator.prepareCommentContinuation(task.id, selected)
            if (updated.errorCode == ErrorCode.NEED_LOGIN.name) { selectionDialog?.dismiss(); openLogin(task.id) }
            else startTask(task.id)
        }
    }
    private fun showTaskDetails(task: UiTask) {
        val resumable = task.stage in listOf(TaskStage.FAILED, TaskStage.CANCELLED, TaskStage.PARTIAL, TaskStage.INTERRUPTED, TaskStage.RESOLVED)
        val message = task.detail.ifBlank { getString(R.string.task_details, task.postId.ifBlank { task.source }, getString(task.mode.label), getString(task.format)) } +
            if (task.mode == DownloadMode.COMMENTS) getString(R.string.target_details, getString(task.targetKind), task.target) else ""
        AlertDialog.Builder(this).setTitle(task.title.ifBlank { getString(R.string.download_content) }).setMessage(message)
            .setNegativeButton(R.string.close, null).apply {
                if (resumable) setPositiveButton(if (task.errorCode == ErrorCode.NEED_LOGIN.name) R.string.login_and_resume else task.retryLabel()) { _, _ -> retryTask(task) }
                if (resumable && task.mode !in listOf(DownloadMode.FAVORITES, DownloadMode.ACCOUNT_WORKS) && coordinator.hasResult(task))
                    setNeutralButton(R.string.view_partial_results) { _, _ -> utility { chooseContent(task) } }
            }.show()
    }
    private fun saveSelection(task: UiTask, assets: List<Asset>, action: OutputAction) {
        if (coordinator.state.value.activeId == task.id && task.mode == DownloadMode.COMMENTS) {
            running = scope.launch {
                try {
                    coordinator.stopForSelection(task.id)
                    coordinator.select(task.id, assets, action)
                    pendingOpenId = task.id
                    screen.selectTab(1); startTask(task.id)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { reportError(e) }
                finally { screen.setWorking(coordinator.busy) }
            }
            return
        }
        utility {
        if (task.format != R.string.format_original) { toast(getString(R.string.conversion_pending)); return@utility }
        coordinator.select(task.id, assets, action)
        if (action == OutputAction.SHARE || task.mode == DownloadMode.COMMENTS) pendingOpenId = task.id
        screen.selectTab(1); startTask(task.id)
        }
    }
    private suspend fun refreshLibrary() {
        val assets = withContext(Dispatchers.IO) { store.all().filter { store.available(it) } }
        screen.renderLibrary(assets)
        assets.filter { it.kind == AssetKind.AUDIO && it.previewFile == null }.map { it.postId }.distinct().forEach { postId ->
            val task = tasks.firstOrNull { it.postId == postId && it.mode != DownloadMode.COMMENTS } ?: return@forEach
            if (coverBackfills.add(postId)) scope.launch {
                try {
                    val media = coordinator.loadPost(task) ?: return@launch
                    if (coordinator.cacheAudioCover(media) != null) screen.renderLibrary(withContext(Dispatchers.IO) { store.all().filter { store.available(it) } })
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { Log.w(TAG, "audio_cover_backfill_unavailable") }
            }
        }
    }

    private fun previewLocal(view: ImageView, file: File) {
        scope.launch {
            try { MediaPreview.load(view, file) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { view.setImageResource(R.drawable.ic_file_image) }
        }
    }
    private fun previewTask(view: ImageView, task: UiTask) {
        val key = task.id.toString()
        if (view.tag == key && view.drawable != null) return
        if (view.tag != key) { view.tag = key; view.setImageDrawable(null) }
        scope.launch {
            try {
                val file = taskThumbnails.getOrPut(key) { scope.async {
                    previewLimit.withPermit {
                        withTimeoutOrNull(config.getLong("preview_timeout_ms")) { coordinator.thumbnail(task) }
                    }
                } }.await()
                if (file == null) { taskThumbnails.remove(key); return@launch }
                if (view.tag == key && view.drawable == null) {
                    val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                    if (view.tag == key && view.drawable == null) view.setImageBitmap(bitmap) else bitmap?.recycle()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { taskThumbnails.remove(key) }
        }
    }
    private fun previewStored(view: ImageView, asset: StoredAsset) {
        if (asset.kind == AssetKind.AUDIO && asset.previewFile?.isFile == true) { previewLocal(view, asset.previewFile); return }
        scope.launch {
            try { MediaPreview.load(view, asset.uri ?: Uri.fromFile(asset.file), asset.file.extension) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { view.setImageResource(R.drawable.ic_file_image) }
        }
    }
    private fun previewRemote(view: ImageView, asset: Asset) {
        remotePreviewScope.launch {
            try {
                val file = remotePreviewFile(asset)
                MediaPreview.load(view, file)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { view.setImageResource(R.drawable.ic_file_image) }
        }
    }
    private suspend fun remotePreviewFile(asset: Asset): File {
        val previewScope = remotePreviewScope
        val directory = previewDirectory
        val loads = previews
        try {
            return loads.getOrPut(asset.id) { previewScope.async {
                previewLimit.withPermit {
                    val stem = MessageDigest.getInstance("SHA-256").digest(asset.id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                    val cached = withContext(Dispatchers.IO) { directory.listFiles()?.firstOrNull { it.nameWithoutExtension == stem && it.extension != "part" } }
                    cached ?: coordinator.downloadPreview(asset, directory, stem).file
                }
            } }.await()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loads.remove(asset.id); throw e }
    }
    private fun clearRemotePreviews() {
        val previous = remotePreviewScope
        val directory = previewDirectory
        previous.cancel()
        remotePreviewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        previewDirectory = File(cacheDir, "previews/${UUID.randomUUID()}")
        previews = mutableMapOf()
        scope.launch {
            previous.coroutineContext[Job]?.join()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }
    private fun showAsset(asset: StoredAsset) {
        selectionDialog?.dismiss()
        selectionDialog = AssetViewer(this, asset, ::previewLocal, {
            utility {
                val verified = withContext(Dispatchers.IO) { store.verified(asset.postId, asset.assetId) }
                    ?: throw AppFailure(ErrorCode.STORAGE, getString(R.string.share_file_missing))
                ShareOptions.intent(this@MainActivity, listOf(verified))?.let { startActivity(Intent.createChooser(it, getString(R.string.share_asset))) }
            }
        }, { saveExistingAsset(asset) }).show()
    }
    private suspend fun shareTask(task: UiTask) {
        val intent = try { ShareOptions.intent(this, coordinator.shareAssets(task)) ?: return }
        catch (e: AppFailure) {
            if (e.code != ErrorCode.STORAGE) throw e
            AlertDialog.Builder(this).setTitle(R.string.share_asset).setMessage(R.string.share_file_missing)
                .setPositiveButton(R.string.prepare_share_again) { _, _ -> retryTask(task) }
                .setNegativeButton(R.string.close, null).show()
            return
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_asset)))
    }
    private fun needsStoragePermission() = Build.VERSION.SDK_INT <= 28 &&
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    private fun saveExistingAsset(asset: StoredAsset) {
        if (needsStoragePermission()) {
            pendingStorageAsset = asset.key
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_REQUEST)
            return
        }
        utility { coordinator.mediaPublisher.save(asset); toast(getString(R.string.system_save_done)) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_REQUEST) return
        val id = pendingStorageTask; pendingStorageTask = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (id != null) pendingResumeId = id
            openPending()
        } else {
            pendingStorageAsset = null; toast(getString(R.string.storage_permission_required))
        }
    }
    @Deprecated("Uses the platform login activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOGIN_REQUEST) {
            val id = resumeAfterLogin; resumeAfterLogin = null
            if (resultCode == RESULT_OK && id != null) {
                if (refreshQualityAfterLogin) pendingQualityId = id else pendingResumeId = id
                openPending()
            }
            refreshQualityAfterLogin = false
            return
        }
    }
    private fun openLogin(taskId: Long? = null, refreshQuality: Boolean = false) {
        if (!ready || coordinator.busy) { toast(getString(R.string.preparing)); return }
        resumeAfterLogin = taskId
        refreshQualityAfterLogin = refreshQuality
        val bili = taskId?.let { coordinator.task(it)?.let(coordinator::taskPlatform) == "bilibili" } ?: (screen.mode == DownloadMode.BILIBILI)
        startActivityForResult(Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.PLATFORM, if (bili) "bilibili" else "douyin")
            .putExtra(LoginActivity.FORCE_LOGIN, taskId?.let { coordinator.task(it)?.errorCode == ErrorCode.NEED_LOGIN.name } == true), LOGIN_REQUEST)
    }
    private fun settings(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.check_bundle)
            menu.add(0, 3, 2, R.string.self_check)
            menu.add(0, 2, 1, R.string.clear_login)
            if (screen.mode != DownloadMode.BILIBILI) {
                menu.add(0, 4, 3, R.string.check_login_state)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> utility { toast(coordinator.manager.update()) }
                    2 -> {
                        coordinator.state.value.activeId?.let { coordinator.cancel(it) }
                        val platform = if (screen.mode == DownloadMode.BILIBILI) "bilibili" else "douyin"
                        coordinator.loginSessions(platform).clear(coordinator.loginConfig(platform)) { refreshAccount(); toast(getString(R.string.login_cleared)) }
                    }
                    3 -> utility { selfCheck() }
                    4 -> utility {
                        val ref = coordinator.adapter.resolve(screen.sourceInput.text.toString())
                        val response = coordinator.adapter.probe(ref)
                        val rows = mapper.required("comments", response) as? JSONArray ?: throw AppFailure(ErrorCode.SCHEMA, "Invalid comments response")
                        toast(getString(R.string.connection_ok, rows.length()))
                    }
                }
                true
            }
            show()
        }
    }
    private fun utility(block: suspend () -> Unit) {
        if (!ready || coordinator.busy || running?.isActive == true) return
        screen.setWorking(true)
        running = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally {
                screen.setWorking(coordinator.busy)
                screen.root.post { if (!isDestroyed) openPending() }
            }
        }
    }
    private suspend fun selfCheck() {
        val api = config.getJSONObject("api")
        val session = JSONObject().put("ua", config.getString("ua")).put("uifid", "offline-self-check").put("now", System.currentTimeMillis() / 1000)
        val detail = coordinator.js.buildUrl(api.getJSONObject("detail").getString("path"), coordinator.signed.parameters("detail", mapOf("postId" to "0")), session)
        val comment = coordinator.js.buildUrl(api.getJSONObject("comment").getString("path"), coordinator.signed.parameters("comment", mapOf("postId" to "0", "cursor" to "0", "count" to config.getInt("count_top").toString())), session)
        fun names(url: String) = URI(url).rawQuery.split('&').map { it.substringBefore('=') }
        check(names(detail).count { it == "a_bogus" } == 1 && names(detail).count { it == "x-secsdk-web-signature" } == 1)
        check(names(comment).count { it == "a_bogus" } == 1 && "x-secsdk-web-signature" !in names(comment))
        val report = JSONObject().put("milestone", "M0").put("bundle_version", coordinator.manager.active.version)
            .put("bundle_verified", true).put("single_abogus", true).put("protected_signature", true)
            .put("comment_without_websign", true).put("network_requested", false)
        withContext(Dispatchers.IO) { FetchLedger.write(File(filesDir, "m0_diagnostics.json"), report) }
        toast(getString(R.string.self_check_passed)); Log.i(TAG, "M0 self_check $report")
        if (BuildConfig.DEBUG) {
            val fixture = JSONObject(assets.open("m1_comments.json").bufferedReader().use { it.readText() })
            val file = File(filesDir, "m1_manifest.json")
            var calls = 0
            val result = CommentFetcher(config, mapper, { operation, variables ->
                calls++
                fixture.getJSONObject("pages").getJSONObject(listOf(operation, variables["parentId"].orEmpty(), variables.getValue("cursor")).joinToString(":"))
            }, { document -> withContext(Dispatchers.IO) { FetchLedger.write(file, document) } }).fetch(PostRef("douyin", fixture.getString("post_id")), 2000, file.absolutePath)
            check(result.fetchedTopLevel == 2 && result.fetchedReplies == 2 && result.assets.size == 2 && calls == 4)
            val code = try { ApiResponse.decode(config, mapper, 200, ""); null } catch (e: AppFailure) { e.code }
            check(code == ErrorCode.NEED_LOGIN)
            Log.i(TAG, "M1 self_check fixture=true network_requested=false top=${result.fetchedTopLevel} replies=${result.fetchedReplies} assets=${result.assets.size} empty_body=$code")
        }
    }
    private fun refreshAccount() {
        if (!ready) return
        val stored = try {
            val session = sessions.read(config.getString("ua")); val names = config.getJSONArray("session_cookie_names")
            (0 until names.length()).any { session.cookie(names.getString(it)).isNotEmpty() }
        } catch (_: AppFailure) { false }
        screen.setAccountStored(stored)
        val biliStored = try {
            coordinator.bilibili?.config?.takeIf { it.has("session_cookie_names") }
                ?.let { coordinator.bilibiliSessions.storedSessionFingerprint(it) != null } == true
        } catch (_: AppFailure) { false }
        screen.setAccountStored(biliStored, bilibili = true)
    }
    private fun reportError(error: Exception) {
        val code = (error as? AppFailure)?.code?.name ?: "ERROR"
        toast(getString(R.string.task_error, code, error.message.orEmpty()))
        Log.w(TAG, "task_failed code=$code type=${error.javaClass.simpleName}")
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onResume() { super.onResume(); resumed = true; if (::screen.isInitialized) { refreshAccount(); openPending() } }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("source", screen.sourceInput.text.toString()); outState.putString("target", screen.targetInput.text.toString())
        outState.putString("mode", screen.mode.name); outState.putInt("target_kind", screen.targetKind); outState.putInt("tab", screen.selectedTab)
        outState.putString("comment_order", screen.commentOrder.name)
        outState.putLong("pending_quality", pendingQualityId ?: 0)
        outState.putBoolean("quality_after_login", refreshQualityAfterLogin)
        outState.putLong("pending_open", pendingOpenId ?: 0); outState.putLong("resume_login", resumeAfterLogin ?: 0)
        outState.putLong("pending_resume", pendingResumeId ?: 0)
        outState.putLong("storage_task", pendingStorageTask ?: 0)
        outState.putString("storage_asset", pendingStorageAsset)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { commentQueryDialog?.dismiss(); selectionDialog?.dismiss(); remotePreviewScope.cancel(); scope.cancel(); super.onDestroy() }
    companion object { private const val TAG = "EmojeApp"; private const val STORAGE_REQUEST = 304; private const val LOGIN_REQUEST = 303 }
}
