package com.emojetools.app.core.bundle

import android.content.Context
import android.util.Log
import com.emojetools.app.BuildConfig
import com.emojetools.app.core.net.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.UUID

class BundleManager(private val context: Context, private val transport: HttpTransport) {
    private val root = File(context.filesDir, "bundles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("bundle_state", Context.MODE_PRIVATE)
    private val verifier = BundleVerifier(context.assets.open("bundle_pubkey.b64").bufferedReader().use { it.readText() }, BuildConfig.VERSION_CODE)
    lateinit var active: VerifiedBundle
        private set

    suspend fun start(): VerifiedBundle = withContext(Dispatchers.IO) {
        val firstInProcess = startupCounted.compareAndSet(false, true)
        val failures = if (firstInProcess && prefs.getBoolean("startup_pending", false)) prefs.getInt("startup_failures", 0) + 1 else prefs.getInt("startup_failures", 0)
        prefs.edit().putBoolean("startup_pending", true).putInt("startup_failures", failures).commit()
        val builtIn = loadBuiltIn()
        val selected = prefs.getString("active", null)?.let { File(root, it) }
        active = if (failures >= 2) {
            prefs.edit().remove("active").putInt("startup_failures", 0).commit()
            Log.w(TAG, "bundle_rollback reason=startup_failures target=${builtIn.version}")
            builtIn
        } else if (selected != null && selected.isDirectory) {
            try {
                val local = verifier.verify(File(selected, "manifest.json").readText(), File(selected, "bundle.zip"))
                if (local.version < builtIn.version) {
                    prefs.edit().remove("active").commit()
                    builtIn
                } else local
            }
            catch (e: Exception) {
                Log.w(TAG, "bundle_rollback reason=invalid_local_bundle", e)
                prefs.edit().remove("active").commit(); builtIn
            }
        } else builtIn
        Log.i(TAG, "bundle_active version=${active.version} files=${active.files.size}")
        active
    }

    fun markHealthy() {
        prefs.edit().putBoolean("startup_pending", false).putInt("startup_failures", 0).commit()
    }

    suspend fun update(): String = withContext(Dispatchers.IO) {
        val sources = JSONObject(context.assets.open("bundle_sources.json").bufferedReader().use { it.readText() }).getJSONArray("manifestUrls")
        if (sources.length() == 0) return@withContext "暂时无法在线更新，当前规则仍可使用"
        var last: Exception? = null
        for (i in 0 until sources.length()) {
            val staging = File(root, ".pending-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val manifestText = transport.execute(Request.Builder().url(sources.getString(i)).build()).use { response ->
                    check(response.isSuccessful) { "Manifest HTTP ${response.code}" }
                    val body = response.body ?: error("Empty manifest")
                    body.byteStream().use { readBounded(it, 256 * 1024) }.toString(Charsets.UTF_8)
                }
                val manifest = verifier.verifyManifest(manifestText)
                if (manifest.getInt("bundle_version") <= active.version) return@withContext "当前规则已是最新版本"
                val archive = File(staging, "bundle.zip")
                val zipUrl = manifest.getString("url")
                require(java.net.URI(zipUrl).scheme == "https")
                transport.execute(Request.Builder().url(zipUrl).build()).use { response ->
                    check(response.isSuccessful) { "Bundle HTTP ${response.code}" }
                    val body = response.body ?: error("Empty archive")
                    body.byteStream().use { input -> archive.writeBytes(readBounded(input, BundleVerifier.MAX_ZIP_BYTES.toInt())) }
                }
                val candidate = verifier.verify(manifestText, archive)
                if (candidate.version <= active.version) return@withContext "当前规则已是最新版本"
                candidate.files.forEach { (path, data) -> File(staging, path).apply { parentFile?.mkdirs(); writeBytes(data) } }
                File(staging, "manifest.json").writeText(manifestText)
                val destination = File(root, "v${candidate.version}-${UUID.randomUUID()}")
                check(staging.renameTo(destination)) { "Atomic bundle installation failed" }
                check(prefs.edit().putString("active", destination.name).commit())
                // The running task keeps its original bundle snapshot. Reload on next app start.
                Log.i(TAG, "bundle_installed version=${candidate.version}")
                return@withContext "规则更新已验证，将在下次打开时生效"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { last = e; Log.w(TAG, "bundle_update_rejected source=$i reason=${e.javaClass.simpleName}") }
            finally { if (staging.exists()) staging.deleteRecursively() }
        }
        throw last ?: IllegalStateException("No bundle source")
    }

    private fun loadBuiltIn(): VerifiedBundle {
        val manifest = context.assets.open("bundle/manifest.json").bufferedReader().use { it.readText() }
        val name = java.net.URI(JSONObject(manifest).getString("url")).path.substringAfterLast('/')
        require(name.matches(Regex("bundle_v[0-9]+\\.zip")))
        val archive = File(root, "builtin.zip")
        context.assets.open("bundle/$name").use { input -> archive.outputStream().use { input.copyTo(it) } }
        return verifier.verify(manifest, archive)
    }

    companion object {
        private const val TAG = "EmojeBundle"
        private val startupCounted = java.util.concurrent.atomic.AtomicBoolean(false)
        fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                require(output.size() + read <= limit) { "Response exceeds size limit" }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
