package com.foundryos.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.foundryos.app.data.AppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases (`Anisghd-lab/ceo-app`), downloads the consistently signed APK
 * directly on the device, and triggers Android's native Package Installer.
 */
class AppUpdateManager(private val context: Context) {
    companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/Anisghd-lab/ceo-app/releases/latest"
        const val RELEASES_LIST_API = "https://api.github.com/repos/Anisghd-lab/ceo-app/releases?per_page=1"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun currentVersionName(): String {
        return runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "0.5.0"
        }.getOrDefault("0.5.0")
    }

    suspend fun checkForUpdate(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val current = currentVersionName()
        try {
            val relObj = fetchReleaseJson(LATEST_RELEASE_API)
                ?: fetchFirstReleaseFromList()
                ?: return@withContext AppUpdateInfo(current, current, false, error = "Unable to reach GitHub Releases")

            val tag = relObj.optString("tag_name").ifBlank { relObj.optString("name") }
            val latestClean = tag.trim().removePrefix("v").removePrefix("V")
            val assets = relObj.optJSONArray("assets") ?: JSONArray()
            var apkUrl = ""
            var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size", 0L)
                    break
                }
            }

            val isNewer = latestClean.isNotBlank() && compareSemver(latestClean, current) > 0 && apkUrl.isNotBlank()
            AppUpdateInfo(
                currentVersion = current,
                latestVersion = latestClean.ifBlank { current },
                hasUpdate = isNewer,
                downloadUrl = apkUrl,
                sizeBytes = apkSize,
                releaseNotes = relObj.optString("body").take(500),
                releaseUrl = relObj.optString("html_url"),
            )
        } catch (e: Exception) {
            AppUpdateInfo(current, current, false, error = e.message ?: "Update check failed")
        }
    }

    suspend fun downloadAndInstall(
        downloadUrl: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (downloadUrl.isBlank()) return@withContext false to "Missing APK download URL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(permIntent) }
            return@withContext false to "Enable 'Allow from this source' for CEO, then tap Install Update again."
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Purge previous update files so Android PackageInstaller never serves a cached URI
        updatesDir.listFiles()?.forEach { runCatching { it.delete() } }

        val stamp = System.currentTimeMillis()
        val targetApk = File(updatesDir, "ceo-update-$stamp.apk")
        val tempApk = File(updatesDir, "ceo-update-$stamp.apk.part")

        try {
            onProgress(0.02f, "Downloading signed APK...")
            val req = Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream, */*")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "CEO-Android-Updater")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false to "Download failed (HTTP ${resp.code})"
                val body = resp.body ?: return@withContext false to "Empty APK download"
                val total = body.contentLength()
                var downloaded = 0L
                val buf = ByteArray(32 * 1024)
                FileOutputStream(tempApk).use { fos ->
                    body.byteStream().use { ins ->
                        while (true) {
                            val read = ins.read(buf)
                            if (read <= 0) break
                            fos.write(buf, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                val frac = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                val mb = downloaded / (1024 * 1024)
                                val totMb = total / (1024 * 1024)
                                onProgress(frac, "Downloading APK... ${mb}/${totMb} MB")
                            }
                        }
                    }
                }
            }

            // Basic ZIP/APK header verification
            val header = tempApk.inputStream().use { ins ->
                ByteArray(2).also { ins.read(it) }
            }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                tempApk.delete()
                return@withContext false to "Downloaded file is not a valid APK"
            }

            if (targetApk.exists()) targetApk.delete()
            if (!tempApk.renameTo(targetApk)) {
                tempApk.copyTo(targetApk, overwrite = true)
                tempApk.delete()
            }

            // Inspect the actual downloaded APK package info to confirm version before launching installer
            val pkgInfo = runCatching {
                context.packageManager.getPackageArchiveInfo(targetApk.absolutePath, 0)
            }.getOrNull()
            val downloadedVersion = pkgInfo?.versionName ?: "latest"

            onProgress(1f, "Launching installer for v$downloadedVersion...")
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetApk)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            context.startActivity(installIntent)
            true to "Installing CEO v$downloadedVersion — tap Update!"
        } catch (e: Exception) {
            false to (e.message ?: "Update install failed")
        }
    }

    private fun fetchReleaseJson(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "CEO-Android-Updater")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
        }
    }

    private fun fetchFirstReleaseFromList(): JSONObject? {
        val req = Request.Builder()
            .url(RELEASES_LIST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CEO-Android-Updater")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else runCatching { JSONArray(resp.body?.string().orEmpty()).optJSONObject(0) }.getOrNull()
        }
    }

    private fun compareSemver(a: String, b: String): Int {
        val pa = a.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
