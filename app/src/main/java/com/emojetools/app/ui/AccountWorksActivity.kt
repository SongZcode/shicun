package com.emojetools.app.ui

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.emojetools.app.R
import com.emojetools.app.TaskApplication
import com.emojetools.app.core.*
import com.emojetools.app.core.store.*
import com.emojetools.app.core.task.TaskService
import com.emojetools.app.platform.douyin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class AccountWorksActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tasks get() = (application as TaskApplication).tasks
    private val platform by lazy { intent.getStringExtra(PLATFORM) ?: "douyin" }
    private val bili get() = platform == "bilibili"
    private val rules get() = if (bili) tasks.bilibili!!.config else tasks.config
    private val settings get() = rules.getJSONObject("account_works")
    private val cache by lazy { AccountWorksStore(File(filesDir, if (bili) "account_works_bilibili" else "account_works")) }
    private var folders = emptyList<Pair<String, String>>()
    private var folderId = ""
    private val cacheLock = Mutex()
    private lateinit var screen: AccountWorksScreen
    private var kind = WorkCollectionKind.SAVED
    private var account: WorkAccount? = null
    private var browserTag: String? = null
    private val collections = mutableMapOf<WorkCollectionKind, AccountWorksCollection>()
    private val errors = mutableMapOf<WorkCollectionKind, AppFailure>()
    private val scroll = mutableMapOf<WorkCollectionKind, Int>()
    private var ready = false
    private var resumed = false
    private var loginOpened = false
    private var loading = false
    private var opening = false
    private var pageGeneration = 0
    private var pageJob: Job? = null
    private var operationJob: Job? = null
    private var previewScope: CoroutineScope? = null
    private var previews: FavoritePreviews? = null
    private var dialog: Dialog? = null
    private var outputId: Long? = null
    private var sharedId: Long? = null
    private var pendingStorage: Long? = null
    private var pendingLoginOutput: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(platform in listOf("douyin", "bilibili"))
        folderId = savedInstanceState?.getString("folder").orEmpty()
        kind = (savedInstanceState?.getString(KIND) ?: intent.getStringExtra(KIND))?.let { name ->
            WorkCollectionKind.entries.firstOrNull { it.name == name }
        } ?: WorkCollectionKind.SAVED
        outputId = savedInstanceState?.getLong("output", 0)?.takeIf { it != 0L }
        sharedId = savedInstanceState?.getLong("shared", 0)?.takeIf { it != 0L }
        pendingStorage = savedInstanceState?.getLong("storage", 0)?.takeIf { it != 0L }
        pendingLoginOutput = savedInstanceState?.getLong("login_output", 0)?.takeIf { it != 0L }
        loginOpened = savedInstanceState?.getBoolean("login_opened") ?: false
        WorkCollectionKind.entries.forEach { tab ->
            scroll[tab] = savedInstanceState?.getInt("${tab.name}_position") ?: 0
        }
        screen = AccountWorksScreen(this, { image, asset -> previews?.load(image, asset, false) },
            ::selectTab, ::loadMore, ::reload, ::login, ::select, ::selectAll, { openWork(it) }, ::output,
            ::retryOutput, { outputId?.let { tasks.cancel(it, asInterrupted = true) } }, ::shareReady, bili)
        setContentView(screen.root)
        screen.render(kind, null, true)
        scope.launch {
            try {
                tasks.initialize(); ready = true
                synchronizeSession(autoLogin = !loginOpened)
                tasks.state.collect { renderOutput(); maybeShare() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failPage(e) }
        }
    }

    private fun sessionTag(): String? = if (!ready) null else try { tasks.accountWorksSessionTag(platform) } catch (_: AppFailure) { null }
    private fun restart(state: AccountWorksCollection) = state.restart().copy(cursor = settings.optString("first_page", "0"))

    private fun synchronizeSession(autoLogin: Boolean = false) {
        if (!ready) return
        val tag = sessionTag()
        if (tag == browserTag && (account != null || pageJob?.isActive == true)) return
        pageGeneration++; pageJob?.cancel(); operationJob?.cancel(); previewScope?.cancel(); dialog?.dismiss()
        collections.clear(); errors.clear(); account = null; loading = false; browserTag = tag
        folders = emptyList()
        outputId?.let { id -> if (tasks.task(id)?.let { it.mode == DownloadMode.ACCOUNT_WORKS && it.sessionTag != tag } == true) outputId = null }
        if (tag == null) {
            cache.clear(); renderCurrent()
            if (autoLogin) login()
            return
        }
        previewScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main).also {
            previews = FavoritePreviews(it, tasks, File(cacheDir, "account-work-previews/$platform/$tag"))
        }
        scope.launch(Dispatchers.IO) {
            File(cacheDir, "account-work-previews/$platform").listFiles()?.filter { it.isDirectory && it.name != tag }?.forEach { it.deleteRecursively() }
        }
        startPage {
            if (!rules.has("account_works")) throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则暂不支持收藏和喜欢作品，请更新规则")
            if (bili) {
                val verified = tasks.bilibili!!.workAccount()
                val availableFolders = tasks.bilibili!!.folders(verified.secUid)
                requireSession(tag)
                account = verified; folders = availableFolders
                if (folders.none { it.first == folderId }) folderId = folders.firstOrNull()?.first.orEmpty()
            }
            val cached = cacheLock.withLock { withContext(Dispatchers.IO) {
                WorkCollectionKind.entries.mapNotNull { cache.read(it, tag, if (it == WorkCollectionKind.SAVED) folderId else "") }
            } }
            requireSession(tag)
            cached.forEach { collections[it.kind] = it }
            account = account ?: cached.firstOrNull()?.account ?: tasks.adapter.workAccount()
            renderCurrent()
            requireSession(tag)
            showOrFetch(tag)
            restoreScroll()
        }
    }

    private fun startPage(block: suspend () -> Unit) {
        val generation = ++pageGeneration
        pageJob?.cancel(); loading = true; errors.remove(kind); renderCurrent()
        pageJob = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == pageGeneration) failPage(e) }
            finally { if (generation == pageGeneration) { loading = false; renderCurrent() } }
        }
    }

    private suspend fun showOrFetch(tag: String) {
        val current = collections[kind]
        if (current != null && System.currentTimeMillis() - current.updatedAt <= settings.getLong("cache_ms")) return
        collections[kind] = current?.let(::restart) ?: AccountWorksCollection(kind, tag, account!!,
            cursor = settings.optString("first_page", "0"), folderId = if (kind == WorkCollectionKind.SAVED) folderId else "")
        if (bili && kind == WorkCollectionKind.SAVED && folderId.isEmpty()) {
            collections[kind] = collections.getValue(kind).copy(completed = true, updatedAt = System.currentTimeMillis())
            return
        }
        fetchPage(kind, tag)
    }

    private fun selectTab(next: WorkCollectionKind) {
        if (next == kind) return
        rememberScroll(); pageGeneration++; pageJob?.cancel(); operationJob?.cancel(); dialog?.dismiss(); opening = false; loading = false
        kind = next; renderCurrent(); renderOutput()
        val tag = sessionTag()
        if (account == null || tag == null || tag != browserTag) { synchronizeSession(); return }
        val current = collections[next]
        val stale = current == null || System.currentTimeMillis() - current.updatedAt > settings.getLong("cache_ms")
        if (stale) startPage { showOrFetch(tag); restoreScroll() } else restoreScroll()
    }
    private fun loadMore() {
        if (!ready || loading) return
        val state = collections[kind]
        if (state == null) { browserTag = null; synchronizeSession(); return }
        if (!state.completed && !state.stopped) startPage { fetchPage(state.kind, state.sessionTag) }
    }
    private fun reload() {
        if (!ready || loading) return
        if (bili && kind == WorkCollectionKind.SAVED && folderId.isEmpty()) {
            browserTag = null; synchronizeSession(); return
        }
        collections[kind]?.let { collections[kind] = restart(it) }
        loadMore()
    }
    private suspend fun fetchPage(tab: WorkCollectionKind, tag: String) {
        requireSession(tag)
        val state = collections[tab] ?: return
        if (state.cursors.isNotEmpty()) delay(if (bili) settings.getLong("page_delay_ms") else tasks.config.getLong("page_delay_ms"))
        val page = if (bili) tasks.bilibili!!.accountWorks(tab, state.account.secUid, state.cursor, state.folderId)
            else tasks.adapter.accountWorks(tab, state.account.secUid, state.cursor)
        requireSession(tag)
        val updated = collections.getValue(tab).append(page, settings.getInt("max_pages"))
        collections[tab] = updated; renderCurrent(); persist(updated)
    }
    private fun selectFolder(next: String) {
        if (next == folderId || folders.none { it.first == next }) return
        pageGeneration++; pageJob?.cancel(); operationJob?.cancel(); dialog?.dismiss(); opening = false
        folderId = next; collections.remove(WorkCollectionKind.SAVED); errors.remove(WorkCollectionKind.SAVED)
        scroll[WorkCollectionKind.SAVED] = 0
        val tag = browserTag ?: return
        startPage {
            val cached = cacheLock.withLock { withContext(Dispatchers.IO) { cache.read(WorkCollectionKind.SAVED, tag, next) } }
            requireSession(tag)
            cached?.let { collections[WorkCollectionKind.SAVED] = it; renderCurrent() }
            showOrFetch(tag); restoreScroll()
        }
    }
    private fun requireSession(tag: String) {
        if (sessionTag() != tag) throw AppFailure(ErrorCode.NEED_LOGIN, getString(R.string.account_login))
    }

    private fun select(id: String, checked: Boolean) {
        val next = collections[kind]?.select(id, checked) ?: return
        collections[kind] = next; renderCurrent(); saveSelection(next)
    }
    private fun selectAll(checked: Boolean) {
        val current = collections[kind] ?: return
        val next = current.copy(selected = if (checked) current.works.map { it.ref.id }.toSet() else emptySet())
        collections[kind] = next; renderCurrent(); saveSelection(next)
    }
    private fun saveSelection(state: AccountWorksCollection) { scope.launch {
        try { persist(state) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { toast(e.message.orEmpty()) }
    } }
    private suspend fun persist(state: AccountWorksCollection) = cacheLock.withLock {
        if (sessionTag() == state.sessionTag) withContext(Dispatchers.IO) { cache.write(state) }
    }

    private fun openWork(work: AccountWork, part: String? = null) {
        val tag = browserTag ?: return
        if (operationJob?.isActive == true || tasks.busy) return
        operationJob = scope.launch {
            opening = true; renderOutput()
            try {
                requireSession(tag)
                val ref = if (bili) tasks.bilibili!!.resolveWork(work.ref).let { if (part == null) it else tasks.bilibili!!.selectPart(it, part) } else work.ref
                val adapter = if (bili) tasks.bilibili!! else tasks.adapter
                val media = PostMedia(adapter.fetchPost(ref), adapter.fetchAssets(ref, QualityPrefs()))
                requireSession(tag)
                val draft = UiTask(0, ref.id, if (bili) DownloadMode.BILIBILI else DownloadMode.POST, R.string.format_original, "", postId = ref.storageId, title = media.post.title)
                dialog?.dismiss()
                dialog = PostSelection(this@AccountWorksActivity, draft, media, { image, asset -> previews?.load(image, asset, false) },
                    onPart = if (bili) { cid -> openWork(work, cid) } else null) { assets, action ->
                    startAction { requireSession(tag); tasks.addPostDownload(media, assets, action) }
                }.show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: getString(R.string.retry)) }
            finally { opening = false; renderOutput() }
        }
    }
    private fun output(action: OutputAction) {
        val state = collections[kind] ?: return
        if (state.selected.isEmpty()) return
        startAction { requireSession(state.sessionTag); tasks.addAccountWorkDownload(state, action) }
    }
    private fun startAction(create: suspend () -> Long) {
        if (operationJob?.isActive == true || tasks.busy) return
        operationJob = scope.launch {
            opening = true; renderOutput()
            try {
                val id = create(); outputId = id; sharedId = null; startOutput(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: getString(R.string.retry)) }
            finally { opening = false; renderOutput() }
        }
    }
    private fun startOutput(id: Long) {
        if (tasks.task(id)?.outputAction == OutputAction.SAVE && Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStorage = id; requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE)
        } else TaskService.start(this, id)
    }
    private fun retryOutput() {
        val id = outputId ?: return
        if (tasks.busy) return
        if (tasks.task(id)?.errorCode == ErrorCode.NEED_LOGIN.name) { pendingLoginOutput = id; login(true) }
        else startOutput(id)
    }
    private fun maybeShare() {
        val task = outputId?.let(tasks::task) ?: return
        if (resumed && task.stage == TaskStage.DONE && task.outputAction == OutputAction.SHARE && sharedId != task.id) shareReady()
    }
    private fun shareReady() {
        val task = outputId?.let(tasks::task) ?: return
        if (task.outputs.isEmpty()) return
        sharedId = task.id
        scope.launch {
            try { ShareOptions.intent(this@AccountWorksActivity, tasks.shareAssets(task))?.let {
                startActivity(Intent.createChooser(it, getString(R.string.share_asset)))
            } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message.orEmpty()) }
        }
    }

    private fun renderCurrent() {
        screen.renderFolders(folders, folderId, bili && kind == WorkCollectionKind.SAVED, ::selectFolder)
        screen.render(kind, collections[kind], loading, errors[kind])
    }
    private fun renderOutput() { screen.renderTask(outputId?.let(tasks::task), tasks.state.value.activeId?.let { it != outputId } == true, opening) }
    private fun failPage(error: Exception) {
        if (sessionTag() != browserTag) {
            previewScope?.cancel(); dialog?.dismiss(); collections.clear(); account = null; browserTag = null; cache.clear()
        }
        errors[kind] = error as? AppFailure ?: AppFailure(ErrorCode.NETWORK, error.message ?: getString(R.string.retry), error)
        loading = false; renderCurrent()
    }
    private fun login() = login(errors[kind]?.code == ErrorCode.NEED_LOGIN)
    private fun login(force: Boolean) {
        if (!ready || tasks.busy) return
        loginOpened = true
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.FORCE_LOGIN, force)
            .putExtra(LoginActivity.PLATFORM, platform), LOGIN)
    }
    @Deprecated("Uses the existing platform login activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOGIN) {
            val pending = pendingLoginOutput; pendingLoginOutput = null
            if (resultCode == RESULT_OK) {
                pageGeneration++; pageJob?.cancel(); browserTag = null; account = null; synchronizeSession()
                if (pending != null) { outputId = pending; startOutput(pending) }
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == STORAGE) {
            val id = pendingStorage; pendingStorage = null
            if (id != null && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startOutput(id)
            else toast(getString(R.string.storage_permission_required))
        }
    }
    private fun rememberScroll() { scroll[kind] = screen.grid.firstVisiblePosition.coerceAtLeast(0) }
    private fun restoreScroll() {
        val position = scroll[kind] ?: return
        val tab = kind
        screen.grid.post {
            val count = screen.grid.count
            // GridView's inherited setSelectionFromTop can layout position -1 in touch mode.
            if (kind == tab && count > 0) screen.grid.setSelection(position.coerceIn(0, count - 1))
        }
    }
    override fun onResume() { super.onResume(); resumed = true; if (ready) { synchronizeSession(); maybeShare() } }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll(); outState.putString(KIND, kind.name)
        outState.putString("folder", folderId)
        scroll.forEach { (tab, position) -> outState.putInt("${tab.name}_position", position) }
        outState.putLong("output", outputId ?: 0); outState.putLong("shared", sharedId ?: 0)
        outState.putLong("storage", pendingStorage ?: 0); outState.putLong("login_output", pendingLoginOutput ?: 0)
        outState.putBoolean("login_opened", loginOpened); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { dialog?.dismiss(); scope.cancel(); super.onDestroy() }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    companion object { const val KIND = "collection_kind"; const val PLATFORM = "platform"; private const val LOGIN = 501; private const val STORAGE = 502 }
}
