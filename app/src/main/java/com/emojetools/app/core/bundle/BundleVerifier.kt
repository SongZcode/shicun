package com.emojetools.app.core.bundle

import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.zip.ZipFile

data class VerifiedBundle(val manifest: JSONObject, val files: Map<String, ByteArray>) {
    val version: Int get() = manifest.getInt("bundle_version")
    fun text(path: String): String = files[path]?.toString(Charsets.UTF_8)
        ?: throw AppFailure(ErrorCode.INVALID_BUNDLE, "Missing bundle file: $path")
}

class BundleVerifier(private val publicKeyBase64: String, private val appVersion: Int) {
    fun verifyManifest(manifestText: String): JSONObject {
        try {
            val m = JSONObject(manifestText)
            require(m.getInt("schema") == 1)
            require(m.getInt("bundle_version") >= 0 && m.getInt("min_app_version") >= 1)
            require(URI(m.getString("url")).scheme == "https")
            listOf("published_at", "url").forEach { require(!m.getString(it).contains(Regex("[\r\n]"))) }
            val payload = listOf("v1", m.getInt("bundle_version").toString(), m.getInt("min_app_version").toString(),
                m.getString("published_at"), m.getString("zip_sha256"), m.getString("files_digest"), m.getString("url"))
                .joinToString("\n").toByteArray(Charsets.UTF_8)
            val verifier = Ed25519Signer()
            val key = Base64.getDecoder().decode(publicKeyBase64.trim())
            require(key.size == 32)
            verifier.init(false, Ed25519PublicKeyParameters(key, 0))
            verifier.update(payload, 0, payload.size)
            require(verifier.verifySignature(Base64.getDecoder().decode(m.getString("sig_ed25519")))) { "Signature mismatch" }
            if (m.getInt("min_app_version") > appVersion) throw AppFailure(ErrorCode.BUNDLE_INCOMPATIBLE, "App update required")
            val list = m.getJSONArray("files")
            require(list.length() in 1..100)
            val entries = (0 until list.length()).map { list.getJSONObject(it) }.sortedBy { it.getString("path") }
            require(entries.map { it.getString("path") }.distinct().size == entries.size) { "Duplicate manifest path" }
            val canonicalFiles = JSONArray().apply { entries.forEach { put(it) } }
            require(CanonicalJson.sha256(CanonicalJson.encode(canonicalFiles).toByteArray(Charsets.UTF_8)) == m.getString("files_digest")) { "files_digest mismatch" }
            return m
        } catch (e: AppFailure) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.INVALID_BUNDLE, "Manifest validation failed: ${e.message}", e) }
    }

    fun verify(manifestText: String, archive: File): VerifiedBundle {
        try {
            val m = verifyManifest(manifestText)
            val files = m.getJSONArray("files")
            val entries = (0 until files.length()).map { files.getJSONObject(it) }
            require(archive.length() in 1..MAX_ZIP_BYTES)
            require(CanonicalJson.sha256(archive.readBytes()) == m.getString("zip_sha256")) { "ZIP hash mismatch" }
            val content = linkedMapOf<String, ByteArray>()
            var total = 0L
            ZipFile(archive).use { zip ->
                val actual = zip.entries().asSequence().toList()
                require(actual.size == entries.size && actual.map { it.name }.toSet() == entries.map { it.getString("path") }.toSet()) { "ZIP file set mismatch" }
                entries.forEach { f ->
                    val path = f.getString("path")
                    require(path.matches(Regex("[A-Za-z0-9_./-]+")) && !path.startsWith("/") && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path" }
                    val size = f.getLong("bytes")
                    require(size in 0..MAX_FILE_BYTES)
                    total += size; require(total <= MAX_EXPANDED_BYTES)
                    val entry = zip.getEntry(path)
                    require(!entry.isDirectory && (entry.size == -1L || entry.size == size))
                    val data = zip.getInputStream(entry).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            require(output.size().toLong() + read <= size) { "Expanded file exceeds declared size" }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    require(data.size.toLong() == size && CanonicalJson.sha256(data) == f.getString("sha256")) { "File hash or length mismatch: $path" }
                    content[path] = data
                }
            }
            return VerifiedBundle(m, content)
        } catch (e: AppFailure) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.INVALID_BUNDLE, "Bundle validation failed: ${e.message}", e) }
    }

    companion object {
        const val MAX_ZIP_BYTES = 20L * 1024 * 1024
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 32L * 1024 * 1024
    }
}
