package com.emojetools.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.emojetools.app.R
import com.emojetools.app.TaskApplication
import com.emojetools.app.core.*
import com.emojetools.app.core.store.FavoriteCollection
import com.emojetools.app.core.store.FavoriteCollectionStore
import com.emojetools.app.core.task.TaskService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class FavoriteStickersActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tasks get() = (application as TaskApplication).tasks
    private val cache by lazy { FavoriteCollectionStore(File(filesDir, "favorites/collection.json")) }
    private val cacheLock = Mutex()
    private lateinit var screen: FavoriteStickersScreen
    private var collection: FavoriteCollection? = null
    private var previews: FavoritePreviews? = null
    private var previewScope: CoroutineScope? = null
    private var pageJob: Job? = null
    private var browserTag: String? = null
    private var error: AppFailure? = null
    private var ready = false
    private var resumed = false
    private var loginOpened = false
    private var outputId: Long? = null
    private var sharedId: Long? = null
    private var pendingStorageId: Long? = null
    private var pendingLoginOutput: Long? = null
    private var sending = false
    private var restorePosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputId = savedInstanceState?.getLong("output", 0)?.takeIf { it != 0L }
        sharedId = savedInstanceState?.getLong("shared", 0)?.takeIf { it != 0L }
        pendingStorageId = savedInstanceState?.getLong("storage", 0)?.takeIf { it != 0L }
        pendingLoginOutput = savedInstanceState?.getLong("login_output", 0)?.takeIf { it != 0L }
        loginOpened = savedInstanceState?.getBoolean("login_opened") ?: false
        restorePosition = savedInstanceState?.getInt("position") ?: 0
        screen = FavoriteStickersScreen(this,
            { view, asset, thumbnail, failed -> previews?.load(view, asset, thumbnail, failed) },
            ::loadMore, ::reload, ::login, ::select, ::selectAll, ::output, ::retryOutput, ::shareReady)
        setContentView(screen.root)
        screen.render(null, true)
        scope.launch {
            try {
                tasks.initialize(); ready = true
                if (!tasks.config.has("favorites")) throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则暂不支持收藏表情包，请更新规则后重试")
                synchronizeSession(autoLogin = !loginOpened)
                tasks.state.collect { state ->
                    val task = outputId?.let(tasks::task)
                    screen.renderTask(task, state.activeId != null && state.activeId != outputId)
                    maybeShare(task)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    private fun sessionTag(): String? = try { tasks.commentCacheTag() } catch (_: AppFailure) { null }

    private fun synchronizeSession(autoLogin: Boolean = false) {
        if (!ready) return
        val tag = sessionTag()
        if (tag == browserTag && (collection != null || pageJob?.isActive == true)) return
        pageJob?.cancel(); previewScope?.cancel(); collection = null; error = null; browserTag = tag
        outputId?.let { id -> if (tasks.task(id)?.sessionTag != tag) outputId = null }
        if (tag == null) {
            cache.clear(); screen.render(null, false)
            if (autoLogin) login()
            return
        }
        previewScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main).also {
            previews = FavoritePreviews(it, tasks, File(cacheDir, "favorite-previews/$tag"))
        }
        scope.launch(Dispatchers.IO) {
            File(cacheDir, "favorite-previews").listFiles()?.filter { it.isDirectory && it.name != tag }?.forEach { it.deleteRecursively() }
        }
        pageJob = scope.launch {
            screen.render(null, true)
            try {
                var saved = cacheLock.withLock { withContext(Dispatchers.IO) { cache.read(tag) } }
                requireSession(tag)
                if (saved == null) {
                    val account = tasks.adapter.currentAccount()
                    requireSession(tag)
                    saved = FavoriteCollection(tag, FavoriteCollection.accountKey(account), account.name)
                } else if (System.currentTimeMillis() - saved.updatedAt > tasks.config.getJSONObject("favorites").getLong("cache_ms")) {
                    saved = restart(saved)
                }
                collection = saved
                if (!saved.completed && !saved.stopped) fetchPage(tag) else screen.render(saved, false)
                if (restorePosition > 0) screen.grid.post {
                    val count = screen.grid.count
                    if (count > 0) screen.grid.setSelection(restorePosition.coerceIn(0, count - 1))
                    restorePosition = 0
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    private fun restart(state: FavoriteCollection) = state.copy(assets = state.assets.filter { it.id in state.selected },
        cursor = "0", cursors = emptySet(), completed = false, stopped = false, updatedAt = 0)

    private fun reload() {
        if (!ready || pageJob?.isActive == true) return
        collection = collection?.let(::restart)
        loadMore()
    }

    private fun loadMore() {
        if (!ready || pageJob?.isActive == true) return
        val state = collection
        if (state == null) { browserTag = null; synchronizeSession(); return }
        if (state.completed || state.stopped) return
        pageJob = scope.launch {
            try { fetchPage(state.sessionTag) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
        }
    }

    private suspend fun fetchPage(tag: String) {
        requireSession(tag)
        val before = collection ?: return
        error = null; screen.render(before, true)
        if (before.cursors.isNotEmpty()) delay(tasks.config.getLong("page_delay_ms"))
        val page = tasks.adapter.favoriteStickers(before.cursor)
        requireSession(tag)
        // Read the current selection after the network wait; the user can keep selecting during paging.
        collection = collection!!.append(page, tasks.config.getJSONObject("favorites").getInt("max_pages"))
        persist()
        screen.render(collection, false)
    }

    private fun requireSession(tag: String) {
        if (sessionTag() != tag) throw AppFailure(ErrorCode.NEED_LOGIN, getString(R.string.favorites_login))
    }

    private fun select(id: String, checked: Boolean) {
        collection = collection?.select(id, checked)
        screen.render(collection, pageJob?.isActive == true, error)
        scope.launch { try { persist() } catch (e: Exception) { if (e is CancellationException) throw e; fail(e) } }
    }
    private fun selectAll(checked: Boolean) {
        collection = collection?.let { it.copy(selected = if (checked) it.assets.map { asset -> asset.id }.toSet() else emptySet()) }
        screen.render(collection, pageJob?.isActive == true, error)
        scope.launch { try { persist() } catch (e: Exception) { if (e is CancellationException) throw e; fail(e) } }
    }
    private suspend fun persist() {
        cacheLock.withLock {
            val state = collection ?: return@withLock
            if (sessionTag() == state.sessionTag) withContext(Dispatchers.IO) { cache.write(state) }
        }
    }

    private fun output(action: OutputAction, ids: Set<String>?) {
        if (!ready || sending || tasks.busy) return
        val state = collection ?: return
        val selected = ids ?: state.selected
        if (selected.isEmpty()) return
        sending = true
        scope.launch {
            try {
                requireSession(state.sessionTag)
                val id = tasks.addFavoriteDownload(state.copy(selected = selected), action)
                outputId = id; sharedId = null
                startOutput(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail(e) }
            finally { sending = false }
        }
    }

    private fun startOutput(id: Long) {
        if (tasks.task(id)?.outputAction == OutputAction.SAVE && Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStorageId = id
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE)
        } else TaskService.start(this, id)
    }
    private fun retryOutput() {
        val id = outputId ?: return
        if (tasks.task(id)?.errorCode == ErrorCode.NEED_LOGIN.name) {
            pendingLoginOutput = id; login(force = true); return
        }
        if (!tasks.busy) startOutput(id)
    }
    private fun maybeShare(task: UiTask?) {
        if (resumed && task?.stage == TaskStage.DONE && task.outputAction == OutputAction.SHARE && sharedId != task.id) shareReady()
    }
    private fun shareReady() {
        val task = outputId?.let(tasks::task) ?: return
        if (task.outputs.isEmpty()) return
        sharedId = task.id
        scope.launch {
            try { ShareOptions.intent(this@FavoriteStickersActivity, tasks.shareAssets(task))?.let {
                startActivity(Intent.createChooser(it, getString(R.string.share_asset)))
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message.orEmpty()) }
        }
    }

    private fun login() = login(error?.code == ErrorCode.NEED_LOGIN)
    private fun login(force: Boolean) {
        if (!ready || tasks.busy) return
        loginOpened = true
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.FORCE_LOGIN, force), LOGIN)
    }
    @Deprecated("Uses the existing platform login activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOGIN) {
            val pending = pendingLoginOutput; pendingLoginOutput = null
            if (resultCode == RESULT_OK) {
                pageJob?.cancel(); browserTag = null; collection = null; synchronizeSession()
                if (pending != null) { outputId = pending; startOutput(pending) }
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == STORAGE) {
            val id = pendingStorageId; pendingStorageId = null
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && id != null) startOutput(id)
            else toast(getString(R.string.storage_permission_required))
        }
    }
    private fun fail(e: Exception) {
        error = e as? AppFailure ?: AppFailure(ErrorCode.NETWORK, e.message ?: getString(R.string.retry), e)
        if (sessionTag() != browserTag) {
            previewScope?.cancel(); collection = null; browserTag = null; cache.clear()
        }
        screen.render(collection, false, error)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onResume() {
        super.onResume(); resumed = true
        if (ready) { synchronizeSession(); maybeShare(outputId?.let(tasks::task)) }
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("output", outputId ?: 0); outState.putLong("shared", sharedId ?: 0)
        outState.putLong("storage", pendingStorageId ?: 0); outState.putBoolean("login_opened", loginOpened)
        outState.putLong("login_output", pendingLoginOutput ?: 0)
        outState.putInt("position", screen.grid.firstVisiblePosition.coerceAtLeast(0))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { screen.close(); scope.cancel(); super.onDestroy() }
    companion object { private const val LOGIN = 401; private const val STORAGE = 402 }
}
