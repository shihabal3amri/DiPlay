package com.shilapi.xcertplay.update

import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
    val releaseNotes: String = "",
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkName = ""
            var apkUrl = ""
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk") && url.isNotBlank()) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true) && url.isNotBlank()) checksumsUrl = url
            }
            if (!isValidHttpsUrl(apkUrl) || !isValidHttpsUrl(checksumsUrl)) continue
            val tagName = release.optString("tag_name")
            if (tagName.isBlank()) continue
            val releaseNotes = release.optString("body")
            return UpdateRelease(tagName, apkName, apkUrl, checksumsUrl, releaseNotes)
        }
        return null
    }

    internal fun isValidHttpsUrl(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val parsed = java.net.URL(value)
            parsed.protocol.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
