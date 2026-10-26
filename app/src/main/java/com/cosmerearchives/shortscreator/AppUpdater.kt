package com.cosmerearchives.shortscreator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AvailableUpdate(val version: String, val code: Int, val url: String, val expectedSize: Long)

object AppUpdater {
    private const val releases = "https://api.github.com/repos/cosmerearchives-creator/Shorts-creator/releases?per_page=20"
    private val tagPattern = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-alpha)?$")
    private fun get(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 12000
        readTimeout = 18000
        setRequestProperty("User-Agent", "ShortsCreator-Android")
        setRequestProperty("Accept", "application/vnd.github+json")
        instanceFollowRedirects = true
    }
    fun latest(context: Context): AvailableUpdate? {
        val connection = get(URL(releases))
        try {
            check(connection.responseCode in 200..299) { "GitHub returned HTTP ${connection.responseCode}" }
            val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
            val current = if (Build.VERSION.SDK_INT >= 33)
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val candidates = (0 until array.length()).mapNotNull { n ->
                val release = array.getJSONObject(n)
                if (release.optBoolean("draft")) return@mapNotNull null
                val tag = release.optString("tag_name")
                val match = tagPattern.matchEntire(tag) ?: return@mapNotNull null
                val (major, minor, patch) = match.destructured
                // The app's legacy versionCode scheme is sequential. The release's
                // versionCode is explicitly published in release notes by CI.
                val code = Regex("versionCode: (\\d+)").find(release.optString("body"))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                if (code <= current) return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") && it.optLong("size") > 0 }
                    ?.let { AvailableUpdate(tag, code, it.getString("browser_download_url"), it.getLong("size")) }
            }
            return candidates.maxByOrNull { it.code }
        } finally { connection.disconnect() }
    }

    fun downloadAndInstall(context: Context, update: AvailableUpdate, progress: (Int) -> Unit) {
        val url = URL(update.url)
        require(url.protocol == "https" && url.host.equals("github.com", true)) { "Untrusted APK source" }
        val file = File(context.cacheDir, "updates/release-${update.code}.apk")
        file.parentFile?.mkdirs()
        val connection = get(url)
        try {
            check(connection.responseCode in 200..299) { "APK download failed: HTTP ${connection.responseCode}" }
            // Redirect must remain HTTPS and on GitHub's trusted asset distribution.
            val resolved = connection.url
            check(resolved.protocol == "https" && (resolved.host.endsWith(".githubusercontent.com") || resolved.host == "github.com")) { "Unexpected download host" }
            var downloaded = 0L
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val chunk = ByteArray(65536)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                        downloaded += count
                        progress(((downloaded * 100) / update.expectedSize).toInt().coerceIn(0, 100))
                    }
                }
            }
            check(downloaded == update.expectedSize) { "Incomplete APK" }
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK file")
            check(archive.packageName == context.packageName) { "APK package ID mismatch" }
            val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            check(archiveVersion == update.code.toLong()) { "Unexpected APK version" }
            @Suppress("DEPRECATION")
            val installed = pm.getPackageInfo(context.packageName, flags)
            val expected = if (Build.VERSION.SDK_INT >= 28)
                installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else @Suppress("DEPRECATION") installed.signatures?.map { it.toCharsString() }?.toSet()
            val actual = if (Build.VERSION.SDK_INT >= 28)
                archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else @Suppress("DEPRECATION") archive.signatures?.map { it.toCharsString() }?.toSet()
            check(!actual.isNullOrEmpty() && actual == expected) { "APK signing identity does not match installed app" }

            val apkUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } finally { connection.disconnect() }
    }
}
