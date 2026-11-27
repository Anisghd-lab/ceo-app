package com.foundryos.app.agy

import com.foundryos.app.data.AgySavedAccount
import org.json.JSONObject
import java.io.File

/**
 * Exact implementation of the AntiGem / agy-accounts Google multi-account switcher:
 * - agy stores its active Google login in $HOME/.gemini/jetski-standalone-oauth-token
 * - Saved accounts live in $HOME/.gemini/ceo-accounts/<email>.json
 * - `writer_id` is stripped on save & restore so agy treats swaps as external changes
 * - Writes use atomic temp file + rename with 0600 permissions
 * - Adding an account removes ONLY the local live token file (never calls AuthLogout, so refresh tokens remain valid)
 */
class AgyAccountManager(private val homeDir: File) {
    private val geminiDir = File(homeDir, ".gemini")
    private val cliDataDir = File(geminiDir, "antigravity-cli")
    private val liveTokenFile = File(geminiDir, "jetski-standalone-oauth-token")
    private val cliTokenFile = File(cliDataDir, "antigravity-oauth-token")
    private val accountsDir = File(geminiDir, "ceo-accounts")

    private fun ensureDirs() {
        geminiDir.mkdirs()
        cliDataDir.mkdirs()
        accountsDir.mkdirs()
    }

    private fun emailOf(token: JSONObject?): String =
        token?.optString("id_token_email", "")?.trim() ?: ""

    private fun authMethodOf(token: JSONObject?): String =
        token?.optString("auth_method", "")?.trim() ?: ""

    private fun tokenKey(token: JSONObject?): String {
        val inner = token?.optJSONObject("token") ?: return ""
        return inner.optString("refresh_token").ifBlank { inner.optString("access_token") }
    }

    private fun cleanToken(token: JSONObject): JSONObject {
        val copy = JSONObject(token.toString())
        copy.remove("writer_id")
        return copy
    }

    private fun accountFileFor(emailOrName: String): File {
        val slug = emailOrName.lowercase().replace(Regex("[^a-z0-9@._-]+"), "_").ifBlank { "account" }
        return File(accountsDir, "$slug.json")
    }

    private fun writeAtomic(target: File, content: String) {
        ensureDirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun readValidTokenFrom(file: File): JSONObject? {
        if (!file.exists()) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (text.isBlank()) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return if (tokenKey(obj).isNotBlank()) obj else null
    }

    fun readLiveToken(): JSONObject? {
        val fromJetski = readValidTokenFrom(liveTokenFile)
        val fromCli = readValidTokenFrom(cliTokenFile)
        if (fromJetski != null && fromCli == null) {
            runCatching { writeAtomic(cliTokenFile, cleanToken(fromJetski).toString()) }
        } else if (fromCli != null && fromJetski == null) {
            runCatching { writeAtomic(liveTokenFile, cleanToken(fromCli).toString()) }
        }
        return fromJetski ?: fromCli
    }

    /**
     * Ensures both `jetski-standalone-oauth-token` (agy --hub) and
     * `antigravity-cli/antigravity-oauth-token` (agy -p / agy models) contain the active OAuth token.
     */
    @Synchronized
    fun ensureTokensSynced(): Boolean {
        val live = readValidTokenFrom(liveTokenFile)
        val cli = readValidTokenFrom(cliTokenFile)
        val chosen = live ?: cli ?: return false
        val cleaned = cleanToken(chosen).toString()
        if (live == null || tokenKey(live) != tokenKey(chosen)) {
            writeAtomic(liveTokenFile, cleaned)
        }
        if (cli == null || tokenKey(cli) != tokenKey(chosen)) {
            writeAtomic(cliTokenFile, cleaned)
        }
        return true
    }

    fun liveEmail(): String = emailOf(readLiveToken())

    fun listSavedAccounts(): List<AgySavedAccount> {
        ensureDirs()
        val live = readLiveToken()
        val liveKey = tokenKey(live)
        val out = mutableListOf<AgySavedAccount>()
        val files = accountsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        for (f in files) {
            val obj = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            val tok = obj.optJSONObject("token") ?: continue
            val key = tokenKey(tok)
            if (key.isBlank()) continue
            val email = obj.optString("email").ifBlank { emailOf(tok) }
            val name = obj.optString("name").ifBlank { email.ifBlank { "Google Account" } }
            out.add(
                AgySavedAccount(
                    name = name,
                    email = email,
                    authMethod = authMethodOf(tok),
                    savedAt = obj.optLong("savedAt", 0L),
                    usedAt = obj.optLong("usedAt", 0L),
                    active = liveKey.isNotBlank() && key == liveKey,
                )
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun isLiveSaved(): Boolean {
        val live = readLiveToken() ?: return false
        val key = tokenKey(live)
        return listSavedAccounts().any { acc ->
            val file = accountFileFor(acc.email.ifBlank { acc.name })
            val tok = runCatching { JSONObject(file.readText()).optJSONObject("token") }.getOrNull()
            tokenKey(tok) == key
        }
    }

    /** Saves the currently signed-in Google account copy (or updates it if same email). */
    @Synchronized
    fun saveCurrentAccount(fallbackEmail: String = ""): Pair<Boolean, String> {
        val live = readLiveToken() ?: return false to "Not signed in to Antigravity yet"
        val email = emailOf(live).ifBlank { fallbackEmail.trim() }.ifBlank { "account_${System.currentTimeMillis() % 10000}" }
        val now = System.currentTimeMillis()
        val targetFile = accountFileFor(email)
        val existingUsed = runCatching { JSONObject(targetFile.readText()).optLong("usedAt", now) }.getOrDefault(now)

        val record = JSONObject()
            .put("name", email)
            .put("email", email)
            .put("savedAt", now)
            .put("usedAt", existingUsed)
            .put("token", cleanToken(live))
        writeAtomic(targetFile, record.toString(2))
        return true to "Saved $email"
    }

    /** Keeps any existing saved copy fresh when token refreshes. */
    @Synchronized
    fun syncLiveIfKnown() {
        val live = readLiveToken() ?: return
        val email = emailOf(live)
        if (email.isBlank()) return
        val target = accountFileFor(email)
        if (target.exists() && !isLiveSaved()) {
            saveCurrentAccount(email)
        }
    }

    /**
     * Switches active Google account atomically by swapping ~/.gemini/jetski-standalone-oauth-token.
     * Caller should restart agy --hub right after so agy reloads the new account cleanly.
     */
    @Synchronized
    fun switchToAccount(email: String): Pair<Boolean, String> {
        // Save current active account first so it is never lost
        if (readLiveToken() != null) {
            saveCurrentAccount()
        }
        val targetFile = accountFileFor(email)
        if (!targetFile.exists()) return false to "Saved account '$email' not found"
        val obj = runCatching { JSONObject(targetFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return false to "Corrupted saved account file"
        val tok = obj.optJSONObject("token") ?: return false to "Missing token in saved account"

        val cleanedStr = cleanToken(tok).toString()
        writeAtomic(liveTokenFile, cleanedStr)
        writeAtomic(cliTokenFile, cleanedStr)
        obj.put("usedAt", System.currentTimeMillis())
        writeAtomic(targetFile, obj.toString(2))
        return true to "Switched to $email"
    }

    /**
     * Clears ONLY the local live login files (without calling AuthLogout, so saved refresh tokens stay valid)
     * so the user can sign in with another Google account.
     */
    @Synchronized
    fun prepareForAnotherGoogleAccount(): Pair<Boolean, String> {
        if (readLiveToken() != null) {
            saveCurrentAccount()
        }
        if (liveTokenFile.exists()) {
            liveTokenFile.delete()
        }
        if (cliTokenFile.exists()) {
            cliTokenFile.delete()
        }
        return true to "Ready to sign in with another Google account"
    }

    @Synchronized
    fun deleteSavedAccount(email: String): Boolean {
        val file = accountFileFor(email)
        return if (file.exists()) file.delete() else false
    }
}
