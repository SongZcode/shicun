package com.emojetools.app.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import com.emojetools.app.core.AppFailure
import com.emojetools.app.core.ErrorCode
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import com.emojetools.app.core.bundle.CanonicalJson
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrl

data class Session(val cookies: String, val userAgent: String) {
    fun cookie(name: String): String = cookies.split(';').map { it.trim() }
        .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "") ?: ""
}

class SessionStore(private val context: Context, namespace: String = "douyin") {
    init { require(namespace.matches(Regex("[a-z0-9-]+"))) }
    private val prefs = context.getSharedPreferences(if (namespace == "douyin") "login" else "login.$namespace", Context.MODE_PRIVATE)
    private val alias = if (namespace == "douyin") "emoje.session.v1" else "emoje.session.$namespace.v1"

    fun read(userAgent: String): Session {
        val encoded = prefs.getString("session", null) ?: return Session("", userAgent)
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val data = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
            if (data.getString("ua") != userAgent) throw AppFailure(ErrorCode.NEED_LOGIN, "登录环境已更新，请重新登录")
            return Session(data.getString("cookies"), userAgent)
        } catch (e: AppFailure) { throw e }
        catch (e: Exception) { throw AppFailure(ErrorCode.NEED_LOGIN, "登录信息无法读取，请重新登录", e) }
    }

    fun webSession(platform: JSONObject): Session {
        val manager = CookieManager.getInstance()
        val origins = platform.optJSONArray("cookie_origins")
        val urls = origins?.let { (0 until it.length()).map(it::getString) } ?: listOf(platform.getString("login_url"))
        val allowed = platform.getJSONArray("cookie_names").let { names -> (0 until names.length()).map { names.getString(it) }.toSet() }
        val cookies = urls.flatMap { (manager.getCookie(it) ?: "").split(';') }.map(String::trim)
            .filter { it.substringBefore('=') in allowed }.distinctBy { it.substringBefore('=') }.joinToString("; ")
        return Session(cookies, platform.getString("ua"))
    }

    fun capture(platform: JSONObject, verified: Session = webSession(platform)): Session {
        require(verified.userAgent == platform.getString("ua"))
        CookieManager.getInstance().flush()
        val cookies = verified.cookies
        val session = Session(cookies, platform.getString("ua"))
        val names = platform.getJSONArray("session_cookie_names")
        if ((0 until names.length()).none { session.cookie(names.getString(it)).isNotEmpty() }) {
            throw AppFailure(ErrorCode.NEED_LOGIN, "尚未获取到登录信息，请完成网页登录")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = JSONObject().put("ua", session.userAgent).put("cookies", session.cookies).toString().toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(data))
        check(prefs.edit().putString("session", encoded).commit())
        return session
    }

    fun webSessionFingerprint(platform: JSONObject): String? {
        val session = if (platform.has("cookie_names")) webSession(platform) else {
            val raw = CookieManager.getInstance().getCookie(platform.getString("login_url")) ?: return null
            Session(raw, platform.getString("ua"))
        }
        return fingerprint(platform, session)
    }

    fun storedSessionFingerprint(platform: JSONObject): String? = fingerprint(platform, read(platform.getString("ua")))

    @Synchronized fun anonymousIdentity(profile: JSONObject): String {
        val digits = profile.getJSONObject("identity").getInt("digits")
        require(digits in 1..32)
        val name = CanonicalJson.sha256(CanonicalJson.encode(profile).toByteArray(Charsets.UTF_8))
        val identities = context.getSharedPreferences("request-identities", Context.MODE_PRIVATE)
        identities.getString(name, null)?.takeIf { it.length == digits && it.all(Char::isDigit) }?.let { return it }
        val random = SecureRandom()
        val value = buildString { append(random.nextInt(9) + 1); repeat(digits - 1) { append(random.nextInt(10)) } }
        check(identities.edit().putString(name, value).commit())
        return value
    }

    private fun fingerprint(platform: JSONObject, session: Session): String? {
        val names = platform.getJSONArray("session_cookie_names")
        val values = (0 until names.length()).map { session.cookie(names.getString(it)) }
        if (values.all { it.isEmpty() }) return null
        return MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun clear(platform: JSONObject, onComplete: () -> Unit) {
        check(prefs.edit().remove("session").commit())
        val origins = platform.optJSONArray("cookie_origins")?.let { rows -> (0 until rows.length()).map(rows::getString) }
            ?: listOf(platform.getString("login_url"))
        val domains = platform.optJSONArray("cookie_domains")?.let { rows -> (0 until rows.length()).map(rows::getString) }
            ?: origins.mapNotNull { it.toHttpUrl().topPrivateDomain()?.let { domain -> ".$domain" } }.distinct()
        val names = platform.getJSONArray("cookie_names").let { rows -> (0 until rows.length()).map(rows::getString) }
        val removals = origins.flatMap { origin ->
            val host = java.net.URI(origin).host
            names.flatMap { name ->
                val cookie = "$name=; Max-Age=0; Path=/; Secure"
                listOf(origin to cookie) + domains.filter { host == it.trimStart('.') || host.endsWith("." + it.trimStart('.')) }
                    .map { origin to "$cookie; Domain=$it" }
            }
        }
        val manager = CookieManager.getInstance()
        if (removals.isEmpty()) { onComplete(); return }
        val remaining = java.util.concurrent.atomic.AtomicInteger(removals.size)
        removals.forEach { (origin, cookie) -> manager.setCookie(origin, cookie) {
            if (remaining.decrementAndGet() == 0) { manager.flush(); onComplete() }
        } }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
}
