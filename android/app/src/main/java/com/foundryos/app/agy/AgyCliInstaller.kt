package com.foundryos.app.agy

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AgyInstallProgress(
    val stage: String,
    val progress: Float, // 0f..1f
    val detail: String,
)

/**
 * Downloads, SHA-512 verifies, and extracts Google's official native Android ARM64 Antigravity CLI (`agy`)
 * directly into app filesDir without requiring Termux or root.
 */
class AgyCliInstaller(private val context: Context) {
    companion object {
        const val MANIFEST_URL =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/android_arm64.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun agyDir(): File = File(context.filesDir, "agy").apply { mkdirs() }
    fun binDir(): File = File(agyDir(), "bin").apply { mkdirs() }
    fun agyBinary(): File = File(binDir(), "agy")
    fun homeDir(): File = File(context.filesDir, "home").apply { mkdirs() }

    fun isArm64Device(): Boolean = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    fun isInstalled(): Boolean {
        val f = agyBinary()
        return f.exists() && f.isFile && f.length() > 1024 * 1024 && f.canExecute()
    }

    suspend fun getInstalledVersion(): String = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext ""
        runCatching {
            val pb = ProcessBuilder(agyBinary().absolutePath, "--version")
            pb.environment()["HOME"] = homeDir().absolutePath
            pb.redirectErrorStream(false)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(8, TimeUnit.SECONDS)
            out.lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        }.getOrDefault("")
    }

    suspend fun installOrUpdate(
        onProgress: suspend (AgyInstallProgress) -> Unit,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!isArm64Device()) {
            return@withContext false to "Antigravity CLI native build requires an arm64-v8a device (${Build.SUPPORTED_ABIS.joinToString()})."
        }

        val archiveFile = File(context.cacheDir, "cli_android_arm64.tar.gz")
        try {
            onProgress(AgyInstallProgress("manifest", 0.05f, "Fetching official Android ARM64 release manifest..."))
            val manifestReq = Request.Builder().url(MANIFEST_URL).get().build()
            val (version, url, expectedSha512) = client.newCall(manifestReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext false to "Manifest HTTP ${resp.code}"
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                Triple(
                    json.optString("version"),
                    json.optString("url"),
                    json.optString("sha512").lowercase(),
                )
            }
            if (url.isBlank() || expectedSha512.isBlank()) {
                return@withContext false to "Invalid Antigravity manifest payload"
            }

            val currentVer = getInstalledVersion()
            if (currentVer == version && isInstalled()) {
                return@withContext true to "agy $version is already up to date"
            }

            onProgress(AgyInstallProgress("download", 0.10f, "Downloading agy $version (~65 MB)..."))
            val dlReq = Request.Builder().url(url).get().build()
            val digest = MessageDigest.getInstance("SHA-512")

            client.newCall(dlReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false to "Download failed HTTP ${resp.code}"
                val body = resp.body ?: return@withContext false to "Empty download response"
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: 65_500_000L
                val buf = ByteArray(64 * 1024)
                var readTotal = 0L
                var lastEmit = 0L

                FileOutputStream(archiveFile).use { fos ->
                    body.byteStream().use { input ->
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            fos.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            readTotal += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 150) {
                                val frac = (0.10f + 0.70f * (readTotal.toFloat() / totalBytes.toFloat())).coerceIn(0.10f, 0.80f)
                                val mb = String.format("%.1f", readTotal / (1024f * 1024f))
                                val totMb = String.format("%.1f", totalBytes / (1024f * 1024f))
                                onProgress(AgyInstallProgress("download", frac, "Downloading agy $version ($mb / $totMb MB)"))
                                lastEmit = now
                            }
                        }
                    }
                }
            }

            onProgress(AgyInstallProgress("verify", 0.82f, "Verifying SHA-512 signature..."))
            val actualSha512 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha512.equals(expectedSha512, ignoreCase = true)) {
                archiveFile.delete()
                return@withContext false to "SHA-512 checksum mismatch — download aborted"
            }

            onProgress(AgyInstallProgress("extract", 0.88f, "Extracting native binary..."))
            val tmpBin = File(binDir(), "agy.tmp")
            var extracted = false
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile), 64 * 1024))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name == "antigravity" || entry.name.endsWith("/antigravity") || entry.name == "agy")) {
                        FileOutputStream(tmpBin).use { out ->
                            tar.copyTo(out)
                        }
                        extracted = true
                        break
                    }
                    entry = tar.nextEntry
                }
            }
            archiveFile.delete()

            if (!extracted || !tmpBin.exists() || tmpBin.length() < 1024 * 1024) {
                tmpBin.delete()
                return@withContext false to "Binary 'antigravity' not found inside archive"
            }

            tmpBin.setReadable(true, false)
            tmpBin.setExecutable(true, false)
            val finalBin = agyBinary()
            if (finalBin.exists()) finalBin.delete()
            if (!tmpBin.renameTo(finalBin)) {
                tmpBin.copyTo(finalBin, overwrite = true)
                tmpBin.delete()
            }
            finalBin.setReadable(true, false)
            finalBin.setExecutable(true, false)

            onProgress(AgyInstallProgress("verify_bin", 0.96f, "Checking executable ($version)..."))
            val verCheck = getInstalledVersion()
            if (verCheck.isBlank()) {
                return@withContext false to "Extracted binary did not execute cleanly (check targetSdk / ABI)"
            }
            onProgress(AgyInstallProgress("done", 1.0f, "Antigravity CLI $verCheck ready"))
            true to "Antigravity CLI $verCheck installed"
        } catch (e: Exception) {
            archiveFile.delete()
            false to (e.message ?: "Install error")
        }
    }
}
