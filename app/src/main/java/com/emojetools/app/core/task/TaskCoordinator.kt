package com.emojetools.app.core.task

import android.content.Context
import android.util.Log
import com.emojetools.app.R
import com.emojetools.app.core.*
import com.emojetools.app.core.bundle.BundleManager
import com.emojetools.app.core.bundle.CanonicalJson
import com.emojetools.app.core.download.*
import com.emojetools.app.core.js.JsEngine
import com.emojetools.app.core.ledger.FetchLedger
import com.emojetools.app.core.net.*
import com.emojetools.app.core.store.*
import com.emojetools.app.platform.douyin.DouyinAdapter
import com.emojetools.app.platform.douyin.AccountWork
import com.emojetools.app.platform.bilibili.BilibiliAdapter
import com.emojetools.app.platform.PlatformAdapter
import com.emojetools.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class TaskState(val tasks: List<UiTask> = emptyList(), val activeId: Long? = null, val ready: Boolean = false, val revision: Long = 0)

open class TaskCoordinator(protected val context: Context, database: String = "assets.db", storageRoot: File? = null) {
    val store = AssetStore(context, database, storageRoot)
    val sessions = SessionStore(context)
    val bilibiliSessions = SessionStore(context, "bilibili")
    val mediaPublisher = MediaPublisher(context, store)
    val manager = BundleManager(context, HttpTransport())
    val js = JsEngine(context.applicationContext)
    lateinit var config: JSONObject; private set
    lateinit var mapper: JsonMapper; private set
    lateinit var signed: SignedHttpClient; private set
    lateinit var adapter: DouyinAdapter; private set
    var bilibili: BilibiliAdapter? = null; private set
    lateinit var downloader: Downloader; private set
    private var bilibiliDownloader: Downloader? = null
    private val initialization = Mutex()
    private val taskChanges = Mutex()
    private val persistence = Mutex()
    private var bundleVersion = 0
    private val mutable = MutableStateFlow(TaskState())
    val state = mutable.asStateFlow()
    private val items = mutableListOf<UiTask>()
    @Volatile private var current: UiTask? = null
    private var activeJob: Job? = null
    private var interrupted = false
    private var progressAt = 0L
    private val liveResults = mutableMapOf<Long, CommentResult>()
    private val audioCovers by lazy { AudioCoverStore(store) { asset, directory, stem ->
        downloadPreview(asset, directory, stem)
    } }
    private val thumbnails by lazy { TaskThumbnailStore(context, store) { asset, directory, stem ->
        downloadPreview(asset, directory, stem)
    } }
    val busy get() = current != null

    suspend fun initialize() = withContext(Dispatchers.Main) {
        initialization.withLock {
            if (mutable.value.ready) return@withLock
            val bundle = manager.start()
            bundleVersion = bundle.version
            config = JSONObject(bundle.text("platforms/douyin.json"))
            mapper = JsonMapper(JSONObject(bundle.text("parsers/douyin.json")))
            js.load(bundle.text("sign/douyin.js"), config)
            if ("platforms/bilibili.json" in bundle.files) {
                val biliConfig = JSONObject(bundle.text("platforms/bilibili.json"))
                val biliJs = JsEngine(context.applicationContext)
                biliJs.load(bundle.text("sign/bilibili.js"), biliConfig)
                bilibili = BilibiliAdapter(biliConfig, JsonMapper(JSONObject(bundle.text("parsers/bilibili.json"))), biliJs, HttpTransport()) {
                    bilibiliSessions.read(biliConfig.getString("ua"))
                }
            }
            val transport = HttpTransport(config.getLong("request_timeout_seconds"))
            signed = SignedHttpClient(config, mapper, js, sessions, transport) { ms ->
                updateProgress(context.getString(R.string.waiting_retry, (ms + 999) / 1000))
            }
            downloader = Downloader(HttpTransport(config.getLong("download_timeout_seconds"), followRedirects = true)) { file, extension ->
                if (extension !in setOf("mp3", "m4a", "mp4")) MediaTools().validateImage(file)
            }
            bilibili?.config?.takeIf { it.has("download_call_timeout_seconds") }?.let { profile ->
                bilibiliDownloader = Downloader(HttpTransport(profile.getLong("download_idle_timeout_seconds"), true,
                    profile.getLong("download_call_timeout_seconds"))) { file, extension ->
                    if (extension !in setOf("mp3", "m4a", "mp4")) MediaTools().validateImage(file)
                }
            }
            adapter = DouyinAdapter(config, transport, sessions, signed, mapper, File(context.filesDir, "fetch")) { top, replies ->
                updateProgress(context.getString(R.string.comments_scanned, top, replies))
            }
            val restored = withContext(Dispatchers.IO) {
                store.taskDocuments().mapNotNull { (id, document) ->
                    try { TaskJson.decode(JSONObject(document), context.resources) }
                    catch (error: Exception) {
                        if (error !is JSONException && error !is IllegalArgumentException) throw error
                        store.deleteTasks(listOf(id))
                        Log.w(TAG, "Discarded unsupported development task record")
                        null
                    }
                }
            }
            items.clear(); items.addAll(restored)
            taskChanges.withLock { trimHistory() }
            withContext(Dispatchers.IO) {
                // No task is executing yet, so abandoned process-local staging files are safe to discard.
                File(context.cacheDir, "sources").listFiles()?.filter { it.name.startsWith("work-") }?.forEach { it.deleteRecursively() }
                File(context.cacheDir, "previews").deleteRecursively()
                store.root.walkTopDown().filter { it.isFile && it.extension == "part" }.forEach { it.delete() }
                store.cleanupTemporaryMedia()
            }
            items.filter { it.stage == TaskStage.INTERRUPTED }.forEach {
                it.detail = interruptionDetail(it, true); persist(it)
            }
            manager.markHealthy()
            publish(ready = true)
            Log.i(TAG, "M3 ready bundle=${bundle.version} recovered=${items.count { it.stage == TaskStage.INTERRUPTED }}")
        }
    }

    suspend fun add(task: UiTask): Long = addPreparedTask(task) {}

    private suspend fun addPreparedTask(task: UiTask, prepare: suspend (UiTask) -> Unit): Long = withContext(Dispatchers.Main) {
        taskChanges.withLock {
            check(!busy)
            val id = maxOf(task.id, (items.maxOfOrNull { it.id } ?: 0) + 1)
            val item = task.copy(id = id, stage = TaskStage.INTERRUPTED)
            prepare(item)
            persist(item); items.add(0, item); trimHistory(); publish(); id
        }
    }
    suspend fun addFavoriteDownload(collection: FavoriteCollection, action: OutputAction): Long = withContext(Dispatchers.Main) {
        check(!busy)
        require(collection.sessionTag == commentCacheTag()) { "登录状态已变化，请重新打开收藏" }
        val selected = collection.assets.filter { it.id in collection.selected }
        require(selected.isNotEmpty())
        val task = UiTask(System.currentTimeMillis(), "", DownloadMode.FAVORITES, R.string.format_original, "",
            title = context.getString(R.string.favorite_stickers), operation = "download", accountName = collection.accountName,
            sessionTag = collection.sessionTag, collectionKey = collection.key, outputAction = action,
            selectedIds = selected.map { it.id }.toMutableSet())
        addPreparedTask(task) { item ->
            withContext(Dispatchers.IO) {
                FetchLedger.write(favoriteFile(item), JSONObject().put("assets", org.json.JSONArray(selected.map(AssetJson::encode))))
            }
        }
    }

    suspend fun addPostDownload(media: PostMedia, selected: List<Asset>, action: OutputAction): Long {
        require(selected.isNotEmpty() && selected.all { chosen -> media.assets.any { it.id == chosen.id } })
        val task = UiTask(System.currentTimeMillis(), media.post.ref.id,
            if (media.post.ref.platform == "bilibili") DownloadMode.BILIBILI else DownloadMode.POST, R.string.format_original, "",
            postId = media.post.ref.storageId, title = media.post.title, operation = "download", outputAction = action,
            selectedIds = selected.map { it.id }.toMutableSet())
        return addPreparedTask(task) { item ->
            withContext(Dispatchers.IO) { FetchLedger.write(postFile(item), PostMediaJson.encode(media)) }
        }
    }

    suspend fun addAccountWorkDownload(collection: AccountWorksCollection, action: OutputAction): Long {
        if (collection.sessionTag != accountWorksSessionTag(collection.account.platform)) throw AppFailure(ErrorCode.NEED_LOGIN, "登录状态已变化，请重新打开作品列表")
        val selected = collection.works.filter { it.ref.id in collection.selected }
        require(selected.isNotEmpty())
        val task = UiTask(System.currentTimeMillis(), collection.kind.name, DownloadMode.ACCOUNT_WORKS, R.string.format_original, "",
            title = context.getString(R.string.account_batch_title, selected.size), operation = "download", outputAction = action,
            selectedIds = selected.map { it.ref.id }.toMutableSet(), sessionTag = collection.sessionTag,
            accountName = collection.account.name, collectionKey = collection.account.key)
        return addPreparedTask(task) { item -> withContext(Dispatchers.IO) {
            FetchLedger.write(accountWorksFile(item), JSONObject().put("works", org.json.JSONArray(selected.map(AccountWorksCollection::encodeWork))))
        } }
    }
    suspend fun delete(id: Long) = withContext(Dispatchers.Main) {
        taskChanges.withLock {
            val target = items.firstOrNull { it.id == id } ?: return@withLock
            if (current?.id == id) { interrupted = false; activeJob?.cancelAndJoin() }
            removeTasks(listOf(target)); publish()
        }
    }
    private suspend fun trimHistory() {
        val removed = items.sortedByDescending { it.id }.filter { it.id != current?.id }.drop(
            MAX_TASKS - if (current != null) 1 else 0)
        if (removed.isNotEmpty()) removeTasks(removed)
    }
    private suspend fun removeTasks(removed: List<UiTask>) {
        persistence.withLock { withContext(Dispatchers.IO) { store.deleteTasks(removed.map { it.id }) } }
        val ids = removed.map { it.id }.toSet()
        items.removeAll { it.id in ids }; ids.forEach(liveResults::remove)
        withContext(Dispatchers.IO) {
            removed.forEach { task ->
                postFile(task).delete(); resultFile(task).delete(); favoriteFile(task).delete(); accountWorksFile(task).delete()
                if (task.postId.matches(Regex("[0-9]+"))) checkpointFile(task).parentFile?.deleteRecursively()
                File(context.filesDir, "post_manifests/${task.id}").deleteRecursively()
                thumbnails.remove(task.id)
            }
        }
        Log.i(TAG, "task_history_removed count=${removed.size} media_preserved=true")
    }
    suspend fun changeCommentQuery(id: Long, kind: Int, query: String, selected: Set<String>? = null): UiTask = withContext(Dispatchers.Main) {
        require(kind in listOf(R.string.target_mine, R.string.target_text, R.string.target_author_name))
        require(kind == R.string.target_mine || query.isNotBlank())
        val account = if (kind == R.string.target_mine && item(id).accountIds.isEmpty()) adapter.currentAccount() else null
        taskChanges.withLock {
            val task = item(id)
            require(task.mode == DownloadMode.COMMENTS)
            val result = liveResults[id] ?: loadResult(task)
            result?.let { previous ->
                val matched = task.matching(previous)
                val allowed = matched.flatMap { it.assetIds }.toSet()
                require(selected == null || allowed.containsAll(selected))
                task.retainedCommentIds = matched.map { it.id }
            }
            selected?.let { task.selectedIds.clear(); task.selectedIds.addAll(it) }
            account?.let { task.accountIds = it.ids; task.accountName = it.name }
            task.targetKind = kind; task.target = query.trim()
            if (current?.id != id) result?.let { previous ->
                val matched = task.matching(previous)
                task.detail = context.getString(R.string.comments_matched, matched.size, matched.flatMap { it.assetIds }.toSet().size)
            }
            persist(task); publish()
            task.copy(selectedIds = task.selectedIds.toMutableSet(), outputs = task.outputs.toMutableMap())
        }
    }
    suspend fun canContinueCommentSearch(task: UiTask): Boolean = commentResumeState(task)?.getString("phase") in setOf("top", "reply")

    private suspend fun commentResumeState(task: UiTask): JSONObject? = withContext(Dispatchers.IO) {
        if (task.mode != DownloadMode.COMMENTS || task.postId.isBlank() || !checkpointFile(task).isFile) return@withContext null
        try {
            val document = JSONObject(checkpointFile(task).readText())
            FetchLedger.restore(document)
            FetchLedger.resumeState(document)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "评论进度已损坏，请新建任务", e) }
    }

    suspend fun prepareCommentContinuation(id: Long, selected: Set<String>): UiTask = withContext(Dispatchers.Main) {
        taskChanges.withLock {
            check(!busy)
            val task = item(id)
            val state = commentResumeState(task) ?: throw AppFailure(ErrorCode.SCHEMA, "没有可继续的评论进度")
            if (state.getString("phase") !in setOf("top", "reply"))
                throw AppFailure(ErrorCode.UNSUPPORTED, "本次查找已结束，可更换条件搜索已有评论")
            val result = CommentResultJson.decode(state.getJSONObject("result"))
            require(task.matching(result).flatMap { it.assetIds }.toSet().containsAll(selected))
            task.selectedIds.clear(); task.selectedIds.addAll(selected)
            task.operation = "discover"; task.stage = TaskStage.INTERRUPTED; task.cacheUsed = false
            task.outputAction = OutputAction.SAVE; task.outputs.clear()
            persist(task); publish()
            Log.i(TAG, "comment_search_continue task=$id phase=${state.getString("phase")}")
            task.copy(selectedIds = task.selectedIds.toMutableSet(), outputs = task.outputs.toMutableMap())
        }
    }
    suspend fun select(id: Long, selected: List<Asset>, action: OutputAction = OutputAction.SAVE) = withContext(Dispatchers.Main) {
        check(!busy && selected.isNotEmpty())
        val task = item(id)
        task.selectedIds.clear(); task.selectedIds.addAll(selected.map { it.id }); task.operation = "download"
        task.outputAction = action; task.outputs.clear()
        task.stage = TaskStage.INTERRUPTED; task.detail = ""
        persist(task); publish()
    }
    fun task(id: Long) = mutable.value.tasks.firstOrNull { it.id == id }
    fun liveResult(id: Long) = liveResults[id]
    fun commentCacheTag(): String? = sessions.storedSessionFingerprint(config)?.let {
        CanonicalJson.sha256("$it:$bundleVersion".toByteArray(Charsets.UTF_8))
    }
    fun accountWorksSessionTag(platform: String): String? = if (platform == "douyin") commentCacheTag() else {
        require(platform == "bilibili")
        bilibiliSessions.storedSessionFingerprint(loginConfig(platform))?.let {
            CanonicalJson.sha256("$platform:$it:$bundleVersion".toByteArray(Charsets.UTF_8))
        }
    }
    fun taskPlatform(task: UiTask) = if (task.mode == DownloadMode.BILIBILI ||
        (task.mode == DownloadMode.ACCOUNT_WORKS && task.collectionKey.startsWith("bilibili-works-"))) "bilibili" else "douyin"
    internal fun commentLookupTag(): String {
        val anonymous = signed.anonymousTag("comment") ?: return commentCacheTag().orEmpty()
        val login = try { commentCacheTag().orEmpty() } catch (_: AppFailure) { "" }
        return CanonicalJson.sha256("$anonymous:$login:$bundleVersion".toByteArray(Charsets.UTF_8))
    }
    suspend fun stopForSelection(id: Long) {
        val job = withContext(Dispatchers.Main) {
            if (current?.id != id) null else { interrupted = true; activeJob }
        }
        job?.cancelAndJoin()
    }
    private fun item(id: Long) = items.firstOrNull { it.id == id } ?: throw AppFailure(ErrorCode.NOT_FOUND, "任务不存在")
    fun cancel(id: Long, asInterrupted: Boolean = false) {
        if (current?.id == id) { interrupted = asInterrupted; activeJob?.cancel() }
    }
    private fun interruptionDetail(task: UiTask, interrupted: Boolean): String =
        context.getString(if (interrupted) R.string.task_recovered else R.string.task_cancelled) +
            if (task.operation == "download") "\n" + context.getString(R.string.download_resume_scope) else ""
    suspend fun run(id: Long) = withContext(Dispatchers.Main) {
        initialize()
        if (busy) return@withContext
        val task = item(id)
        liveResults.clear()
        current = task; activeJob = currentCoroutineContext().job; interrupted = false
        task.stage = if (task.operation == "download") TaskStage.DOWNLOADING else TaskStage.RESOLVING
        task.errorCode = ""; task.detail = ""; publish()
        try {
            withContext(Dispatchers.IO) { store.cleanupTemporaryMedia() }
            persist(task)
            execute(task)
        } catch (e: CancellationException) {
            task.stage = if (interrupted) TaskStage.INTERRUPTED else TaskStage.CANCELLED
            task.detail = interruptionDetail(task, interrupted)
            throw e
        } catch (e: Exception) {
            task.stage = TaskStage.FAILED; task.errorCode = (e as? AppFailure)?.code?.name.orEmpty()
            task.detail = e.message.orEmpty()
            if (e is CommentFetchFailure) {
                try { rememberResult(task, e.partial) }
                catch (storage: Exception) {
                    task.detail += "\n" + context.getString(R.string.task_storage_failed)
                    Log.e(TAG, "partial_result_save_failed type=${storage.javaClass.simpleName}")
                }
            }
            Log.w(TAG, "task=${task.id} failed code=${task.errorCode}")
        } finally {
            try {
                withContext(NonCancellable) {
                    try { persist(task) }
                    catch (storage: Exception) {
                        task.stage = TaskStage.FAILED; task.errorCode = "STORAGE"
                        task.detail = context.getString(R.string.task_storage_failed)
                        Log.e(TAG, "task_save_failed type=${storage.javaClass.simpleName}")
                    }
                }
            }
            finally { current = null; activeJob = null; publish() }
        }
    }

    protected open suspend fun execute(task: UiTask) {
        if (task.operation == "download") save(task) else discover(task)
    }
    private suspend fun discover(task: UiTask) {
        val platform = platformAdapter(task.mode)
        val ref = if (task.mode == DownloadMode.BILIBILI) loadPost(task)?.post?.ref ?: platform.resolve(task.source)
            else if (task.postId.isNotEmpty()) PostRef("douyin", task.postId) else adapter.resolve(task.source)
        task.postId = ref.storageId; persist(task)
        if (task.mode == DownloadMode.COMMENTS) {
            val sessionTag = commentLookupTag()
            if (checkpointFile(task).isFile && task.sessionTag != sessionTag)
                throw AppFailure(ErrorCode.SCHEMA, "评论规则或登录状态已更新，请新建查找")
            task.sessionTag = sessionTag
            if (task.targetType() == CommentTarget.MINE) {
                val account = adapter.currentAccount()
                if (task.accountIds.isNotEmpty() && task.accountIds.none { it in account.ids })
                    throw AppFailure(ErrorCode.UNSUPPORTED, "登录账号已切换，请新建“我的评论”任务")
                task.accountIds = account.ids; task.accountName = account.name
            }
            if (!task.forceRefresh && !checkpointFile(task).isFile) {
                val cachedTask = items.firstOrNull { it.id != task.id && it.postId == ref.id &&
                    it.mode == DownloadMode.COMMENTS && task.sessionTag.isNotEmpty() && it.sessionTag == task.sessionTag &&
                    it.commentOrder == task.commentOrder &&
                    it.commentsFetchedAt > 0 && System.currentTimeMillis() - it.commentsFetchedAt in 0..config.getLong("comment_cache_ms") &&
                    resultFile(it).isFile && checkpointFile(it).isFile }
                if (cachedTask != null) {
                    val cached = loadResult(cachedTask)
                    if (cached != null) {
                        task.cacheUsed = true; task.commentsFetchedAt = cachedTask.commentsFetchedAt
                        rememberResult(task, copyCachedResult(cachedTask, task, cached))
                        task.detail = context.getString(R.string.comment_cached)
                        task.stage = TaskStage.SELECTING; return
                    }
                }
            }
            task.cacheUsed = false
            task.stage = TaskStage.FETCHING; publish(); persist(task)
            val result = adapter.fetchCommentAssetsForTask(ref, config.getInt("limit_top"), task.id.toString(), task.commentOrder) { partial ->
                liveResults[task.id] = partial
                publish()
            }
            task.commentsFetchedAt = System.currentTimeMillis()
            rememberResult(task, result)
            val matched = task.matching(result)
            task.detail = context.getString(R.string.comments_matched, matched.size, matched.flatMap { it.assetIds }.toSet().size)
        } else {
            val post = platform.fetchPost(ref)
            val assets = platform.fetchAssets(ref, QualityPrefs())
            withContext(Dispatchers.IO) { FetchLedger.write(postFile(task), PostMediaJson.encode(PostMedia(post, assets))) }
            task.title = post.title
            task.detail = context.getString(R.string.work_media_count, assets.count { it.kind == AssetKind.VIDEO }, assets.count { it.kind == AssetKind.IMAGE })
        }
        task.stage = TaskStage.SELECTING
    }
    private suspend fun save(task: UiTask) {
        if (task.format != R.string.format_original) throw AppFailure(ErrorCode.UNSUPPORTED, context.getString(R.string.conversion_pending))
        if (task.mode == DownloadMode.COMMENTS) {
            val previous = task.outputs.toMap()
            val valid = withContext(Dispatchers.IO) {
                previous.filter { (id, uri) ->
                    id in task.selectedIds && store.verified(task.postId, id)?.let { stored ->
                        (if (task.outputAction == OutputAction.SAVE) stored.uri else MediaSharing.uri(context, stored))?.toString() == uri
                    } == true
                }
            }
            task.outputs.clear(); task.outputs.putAll(valid)
        } else task.outputs.clear()
        persist(task)
        suspend fun deliver(asset: StoredAsset) {
            val uri = if (task.outputAction == OutputAction.SAVE) mediaPublisher.save(asset) else {
                if (asset.uri == null) asset.file.setLastModified(System.currentTimeMillis())
                MediaSharing.uri(context, asset)
            }
            withContext(Dispatchers.Main) {
                task.outputs[if (task.mode == DownloadMode.ACCOUNT_WORKS) asset.key else asset.assetId] = uri.toString(); persist(task)
            }
        }
        val summary = if (task.mode == DownloadMode.ACCOUNT_WORKS) {
            val platformId = taskPlatform(task)
            val session = accountWorksSessionTag(platformId) ?: throw AppFailure(ErrorCode.NEED_LOGIN, "请登录后继续下载作品")
            if (session != task.sessionTag) {
                val account = if (platformId == "bilibili") bilibili!!.workAccount() else adapter.workAccount()
                if (account.key != task.collectionKey) throw AppFailure(ErrorCode.UNSUPPORTED, "登录账号已切换，请在作品列表重新选择")
                task.sessionTag = session; task.accountName = account.name; persist(task)
            }
            val works = loadAccountWorks(task).filter { it.ref.id in task.selectedIds }
            if (works.isEmpty() || works.size != task.selectedIds.size) throw AppFailure(ErrorCode.SCHEMA, "所选作品记录不完整，请重新选择")
            WorkBatchDownloader(store, File(context.cacheDir, "sources"), MediaTools(), { ref ->
                if (ref.platform == "bilibili") {
                    val resolved = bilibili!!.resolveWork(ref)
                    PostMedia(bilibili!!.fetchPost(resolved), bilibili!!.fetchAssets(resolved, QualityPrefs()))
                } else PostMedia(adapter.fetchPost(ref), adapter.fetchAssets(ref, QualityPrefs()))
            }, { ref -> if (ref.platform == "bilibili") bilibili!!.fetchAssets(ref, QualityPrefs()) else adapter.refreshPostAssets(ref) },
                ::download, config.getInt("asset_refresh_attempts"), ::deliver)
                .save(works, File(context.filesDir, "post_manifests/${task.id}")) { n, total, title ->
                    updateProgress(context.getString(R.string.account_work_progress, n, total, title.ifBlank { context.getString(R.string.untitled_work) }))
                }
        } else if (task.mode == DownloadMode.FAVORITES) {
            val session = commentCacheTag() ?: throw AppFailure(ErrorCode.NEED_LOGIN, "请登录抖音后继续保存收藏表情包")
            if (session != task.sessionTag) {
                val account = adapter.currentAccount()
                if (FavoriteCollection.accountKey(account) != task.collectionKey)
                    throw AppFailure(ErrorCode.UNSUPPORTED, "登录账号已切换，请在收藏页重新选择")
                task.sessionTag = session; task.accountName = account.name; persist(task)
            }
            FavoriteDownloader(store, ::deliver, ::download).save(task.collectionKey, selection(task, loadFavorites(task))) { n, total ->
                updateProgress(context.getString(R.string.download_progress, n, total))
            }
        } else if (task.mode == DownloadMode.COMMENTS) {
            val result = loadResult(task) ?: throw AppFailure(ErrorCode.SCHEMA, "评论记录已丢失，请新建任务")
            val matched = task.matching(result)
            val selected = selection(task, result.assets).map { asset ->
                val owner = matched.firstOrNull { asset.id in it.assetIds }
                    ?: throw AppFailure(ErrorCode.SCHEMA, "所选素材不属于匹配的评论，请重新选择")
                asset.copy(meta = asset.meta + mapOf("commentId" to owner.id, "level" to if (owner.parentId == null) "T" else "R"))
            }
            val pending = selected.filterNot { it.id in task.outputs }
            val completed = selected.size - pending.size
            if (pending.isEmpty()) PostDownloadSummary(0, 0, emptyList())
            else CommentDownloader(store, ::deliver) { asset, directory, stem -> download(asset, directory, stem) }.save(task.postId, pending, result) { n, _ ->
                updateProgress(context.getString(R.string.download_progress, completed + n, selected.size))
            }
        } else {
            val media = loadPost(task) ?: throw AppFailure(ErrorCode.SCHEMA, "作品记录已丢失，请新建任务")
            if (selection(task, media.assets).any { it.kind == AssetKind.AUDIO }) cacheAudioCover(media)
            PostDownloader(store, File(context.cacheDir, "sources"), MediaTools(), ::download,
                { if (media.post.ref.platform == "bilibili") platformAdapter(DownloadMode.BILIBILI).fetchAssets(media.post.ref, QualityPrefs())
                    else adapter.refreshPostAssets(media.post.ref) }, config.getInt("asset_refresh_attempts"), ::deliver)
                .save(media, selection(task, media.assets), File(context.filesDir, "post_manifests/${task.id}/manifest.json")) { n, total, _ ->
                    updateProgress(context.getString(R.string.download_progress, n, total))
                }
        }
        val saved = if (task.mode == DownloadMode.COMMENTS) task.outputs.size else summary.saved
        val failed = if (task.mode == DownloadMode.COMMENTS) task.selectedIds.size - saved else summary.failed
        task.stage = if (failed == 0) TaskStage.DONE else if (saved > 0) TaskStage.PARTIAL else TaskStage.FAILED
        task.detail = context.getString(if (task.outputAction == OutputAction.SHARE) R.string.share_summary else R.string.download_summary, saved, failed)
        val failure = summary.failures.firstOrNull { it.code == ErrorCode.NEED_LOGIN } ?: summary.failures.firstOrNull()
        failure?.let { task.errorCode = it.code.name; task.detail += "\n${it.message}" }
    }
    suspend fun shareAssets(task: UiTask): List<StoredAsset> = withContext(Dispatchers.IO) {
        val ready = if (task.mode == DownloadMode.ACCOUNT_WORKS) {
            val entries = store.all().associateBy { it.key }
            task.outputs.keys.map { key -> entries[key]?.let { store.verified(it.postId, it.assetId) }
                ?: throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.share_file_missing)) }
        } else task.selectedIds.filter { it in task.outputs }.map { id -> store.verified(task.storageId, id)
            ?: throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.share_file_missing)) }
        if (ready.isEmpty()) throw AppFailure(ErrorCode.STORAGE, context.getString(R.string.share_file_missing))
        ready
    }
    suspend fun shareIntent(task: UiTask) = MediaSharing.intent(context, shareAssets(task))

    fun loginConfig(platform: String): JSONObject = if (platform == "bilibili")
        bilibili?.config?.takeIf { it.has("login_url") } ?: throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持 B 站登录") else {
        require(platform == "douyin"); config
    }
    fun loginSessions(platform: String): SessionStore = if (platform == "bilibili") bilibiliSessions else {
        require(platform == "douyin"); sessions
    }
    suspend fun refreshBilibili(id: Long) = withContext(Dispatchers.Main) {
        check(!busy)
        val task = item(id)
        require(task.mode == DownloadMode.BILIBILI)
        val previous = loadPost(task) ?: return@withContext
        val platform = platformAdapter(task.mode)
        val updated = PostMedia(platform.fetchPost(previous.post.ref), platform.fetchAssets(previous.post.ref, QualityPrefs()))
        withContext(Dispatchers.IO) { FetchLedger.write(postFile(task), PostMediaJson.encode(updated)) }
        task.selectedIds.retainAll(updated.assets.map { it.id }.toSet())
        persist(task); publish()
    }
    private fun selection(task: UiTask, all: List<Asset>): List<Asset> {
        val selected = all.filter { it.id in task.selectedIds }
        if (selected.isEmpty() || selected.size != task.selectedIds.size) throw AppFailure(ErrorCode.SCHEMA, "所选素材记录不完整，请重新选择")
        return selected
    }
    private suspend fun copyCachedResult(source: UiTask, target: UiTask, result: CommentResult): CommentResult = withContext(Dispatchers.IO) {
        val document = JSONObject(checkpointFile(source).readText())
        val ledger = FetchLedger.restore(document)
        val state = FetchLedger.resumeState(document)
        val copied = result.copy(ledgerPath = checkpointFile(target).absolutePath)
        state.put("result", CommentResultJson.encode(copied))
        ledger.extras.put("resume", state).put("resume_sha256", CanonicalJson.sha256(CanonicalJson.encode(state).toByteArray(Charsets.UTF_8)))
            .put("cache_source_task", source.id).put("cache_fetched_at", source.commentsFetchedAt)
        FetchLedger.write(checkpointFile(target), ledger.snapshot(finished = true))
        copied
    }
    suspend fun cacheAudioCover(media: PostMedia): File? {
        return try {
            withTimeoutOrNull(config.getLong("preview_timeout_ms")) { audioCovers.ensure(media) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "audio_cover_unavailable type=${e.javaClass.simpleName}"); null
        }
    }
    private fun platformAdapter(mode: DownloadMode): PlatformAdapter = if (mode == DownloadMode.BILIBILI)
        bilibili ?: throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持 B 站，请更新规则") else adapter

    fun inputMode(source: String, fallback: DownloadMode) = if (bilibili?.matches(source) == true) DownloadMode.BILIBILI else fallback

    suspend fun selectBilibiliPart(id: Long, cid: String) = withContext(Dispatchers.Main) {
        taskChanges.withLock {
            check(!busy)
            val task = item(id)
            require(task.mode == DownloadMode.BILIBILI)
            val previous = loadPost(task) ?: throw AppFailure(ErrorCode.SCHEMA, "视频记录已丢失")
            require(previous.post.parts.any { it.id == cid })
            val ref = bilibili!!.selectPart(previous.post.ref, cid)
            val platform = platformAdapter(task.mode)
            val media = PostMedia(platform.fetchPost(ref), platform.fetchAssets(ref, QualityPrefs()))
            withContext(Dispatchers.IO) { FetchLedger.write(postFile(task), PostMediaJson.encode(media)) }
            task.postId = ref.storageId; task.title = media.post.title
            task.selectedIds.clear(); task.outputs.clear(); task.operation = "discover"; task.stage = TaskStage.SELECTING
            persist(task); publish()
        }
    }

    private fun mediaHeaders(asset: Asset): Pair<String, String> {
        val platform = if (asset.meta["platform"] == "bilibili")
            (bilibili ?: throw AppFailure(ErrorCode.UNSUPPORTED, "当前规则不支持 B 站")).config else config
        val ua = if (asset.meta["platform"] == "bilibili") platform.getString("ua") else sessions.read(config.getString("ua")).userAgent
        return ua to platform.getString("referer")
    }
    suspend fun downloadPreview(asset: Asset, directory: File, stem: String): DownloadedFile {
        val (ua, referer) = mediaHeaders(asset)
        return downloader.download(asset, directory, stem, ua, referer, config.getLong("max_preview_bytes"))
    }
    private suspend fun download(asset: Asset, directory: File, stem: String): DownloadedFile {
        val (ua, referer) = mediaHeaders(asset)
        val bili = asset.meta["platform"] == "bilibili"
        val sourceDownloader = if (bili) bilibiliDownloader ?: downloader else downloader
        val maxBytes = if (bili) bilibili!!.config.optLong("max_asset_bytes", config.getLong("max_asset_bytes")) else config.getLong("max_asset_bytes")
        suspend fun fetch(source: Asset, folder: File, name: String): DownloadedFile = try {
            sourceDownloader.download(source, folder, name, ua, referer, maxBytes) { bytes ->
            updateProgress(context.getString(R.string.download_bytes, android.text.format.Formatter.formatShortFileSize(context, bytes)), throttle = true)
        }
        } catch (e: AppFailure) {
            val reason = e.cause?.message.orEmpty()
            val safeReason = if (reason.matches(Regex("CDN HTTP [0-9]{3}")) || reason in setOf("Unrecognized media content", "Unexpected media content")) reason
                else e.cause?.javaClass?.simpleName.orEmpty()
            Log.w(TAG, "asset_download_failed kind=${source.kind} code=${e.code} reason=$safeReason")
            throw e
        }
        if (asset.meta["transform"] != "dash_mux") return fetch(asset, directory, stem)
        return withContext(Dispatchers.IO) {
            check(directory.isDirectory || directory.mkdirs())
            val staging = File(context.cacheDir, "sources/work-${java.util.UUID.randomUUID()}").apply { check(mkdirs()) }
            try {
                val video = fetch(asset, staging, "video")
                val urls = org.json.JSONArray(asset.meta.getValue("audioUrls"))
                val audio = fetch(asset.copy(kind = AssetKind.AUDIO, urls = (0 until urls.length()).map(urls::getString)), staging, "audio")
                MediaTools().muxTracks(video.file, audio.file, File(directory, "$stem.mp4"))
            } finally { staging.deleteRecursively() }
        }
    }
    private fun updateProgress(message: String, throttle: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (throttle && now - progressAt < 400) return
        progressAt = now
        val id = current?.id ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            current?.takeIf { it.id == id }?.let { it.detail = message; publish() }
        }
    }
    private fun publish(ready: Boolean = mutable.value.ready) {
        mutable.value = TaskState(items.map { it.copy(selectedIds = it.selectedIds.toMutableSet(), outputs = it.outputs.toMutableMap()) }, current?.id, ready, mutable.value.revision + 1)
    }
    suspend fun persist(task: UiTask) {
        persistence.withLock {
            val json = TaskJson.encode(task, context.resources)
            withContext(Dispatchers.IO) { store.saveTask(task.id, json) }
        }
    }
    fun postFile(task: UiTask) = File(context.filesDir, "post_results/${task.id}.json")
    fun resultFile(task: UiTask) = File(context.filesDir, "comment_results/${task.id}.json")
    private fun favoriteFile(task: UiTask) = File(context.filesDir, "favorite_results/${task.id}.json")
    private fun accountWorksFile(task: UiTask) = File(context.filesDir, "account_work_results/${task.id}.json")
    suspend fun loadAccountWorks(task: UiTask): List<AccountWork> = withContext(Dispatchers.IO) {
        accountWorksFile(task).takeIf { it.isFile }?.let { file ->
            JSONObject(file.readText()).getJSONArray("works").let { rows -> (0 until rows.length()).map { AccountWorksCollection.decodeWork(rows.getJSONObject(it)) } }
        }.orEmpty()
    }
    suspend fun loadFavorites(task: UiTask): List<Asset> = withContext(Dispatchers.IO) {
        favoriteFile(task).takeIf { it.isFile }?.let { file ->
            JSONObject(file.readText()).getJSONArray("assets").let { rows -> (0 until rows.length()).map { AssetJson.decode(rows.getJSONObject(it)) } }
        }.orEmpty()
    }
    fun checkpointFile(task: UiTask) = File(context.filesDir, "fetch/${task.postId}/${task.id}/manifest.json")
    fun hasResult(task: UiTask) = if (task.mode == DownloadMode.COMMENTS)
        resultFile(task).isFile || (task.postId.isNotBlank() && checkpointFile(task).isFile)
        else if (task.mode == DownloadMode.FAVORITES) favoriteFile(task).isFile
        else if (task.mode == DownloadMode.ACCOUNT_WORKS) accountWorksFile(task).isFile else postFile(task).isFile
    private suspend fun rememberResult(task: UiTask, result: CommentResult) = withContext(Dispatchers.IO) {
        FetchLedger.write(resultFile(task), CommentResultJson.encode(result))
        withContext(Dispatchers.Main) { liveResults[task.id] = result }
    }
    suspend fun loadPost(task: UiTask): PostMedia? = withContext(Dispatchers.IO) {
        postFile(task).takeIf { it.isFile }?.let { PostMediaJson.decode(JSONObject(it.readText())) }
    }
    suspend fun thumbnail(task: UiTask): File? {
        if (items.none { it.id == task.id }) return null
        val assets = if (task.mode == DownloadMode.ACCOUNT_WORKS) loadAccountWorks(task).mapNotNull { it.cover() }
        else if (task.mode == DownloadMode.FAVORITES) loadFavorites(task) else if (task.mode == DownloadMode.COMMENTS) {
            val result = liveResults[task.id] ?: loadResult(task)
            val matched = result?.let(task::matching)?.flatMap { it.assetIds }?.toSet().orEmpty()
            result?.assets?.filter { it.id in matched }.orEmpty().sortedBy { if (it.id in task.selectedIds) 0 else 1 }
        } else loadPost(task)?.assets.orEmpty().sortedBy { if (it.kind == AssetKind.COVER) 0 else 1 }
        val visual = assets.filter { it.kind != AssetKind.AUDIO }
        val (stored, cover) = withContext(Dispatchers.IO) {
            val saved = visual.firstNotNullOfOrNull { store.find(task.storageId, it.id)?.takeIf(store::available) }
            saved to if (task.mode != DownloadMode.COMMENTS) store.previewFile(task.postId) else null
        }
        val asset = stored?.let { saved -> visual.first { it.id == saved.assetId } } ?: visual.firstOrNull()
        val file = thumbnails.ensure(task.id, asset, stored, cover)
        if (items.none { it.id == task.id }) { thumbnails.remove(task.id); return null }
        return file
    }
    suspend fun loadResult(task: UiTask): CommentResult? = withContext(Dispatchers.IO) {
        try {
            val checkpoint = checkpointFile(task)
            if (task.postId.isNotBlank() && checkpoint.isFile) {
                val document = JSONObject(checkpoint.readText())
                FetchLedger.restore(document)
                CommentResultJson.decode(FetchLedger.resumeState(document).getJSONObject("result"))
            } else resultFile(task).takeIf { it.isFile }?.let { CommentResultJson.decode(JSONObject(it.readText())) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.SCHEMA, "评论结果已损坏，请新建任务", e) }
    }
    companion object { const val MAX_TASKS = 20; private const val TAG = "EmojeTasks" }
}
