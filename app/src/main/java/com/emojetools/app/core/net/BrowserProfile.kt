package com.emojetools.app.core.net

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

object BrowserProfile {
    fun apply(settings: WebSettings, platform: JSONObject): Boolean {
        settings.userAgentString = platform.getString("ua")
        val profile = platform.optJSONObject("ua_metadata") ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return false
        val brands = profile.getJSONArray("brands")
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList((0 until brands.length()).map { index ->
                val brand = brands.getJSONObject(index)
                UserAgentMetadata.BrandVersion.Builder().setBrand(brand.getString("brand"))
                    .setMajorVersion(brand.getString("major")).setFullVersion(brand.getString("full")).build()
            })
            .setFullVersion(profile.getString("full_version"))
            .setPlatform(profile.getString("platform")).setPlatformVersion(profile.getString("platform_version"))
            .setArchitecture(profile.getString("architecture")).setModel(profile.getString("model"))
            .setMobile(profile.getBoolean("mobile")).setBitness(profile.getInt("bitness"))
            .setWow64(profile.getBoolean("wow64")).build()
        WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        return true
    }
}
