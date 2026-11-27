package com.foundryos.app.agy

import android.content.Context
import android.content.SharedPreferences
import com.foundryos.app.data.AgyAuthState
import com.foundryos.app.data.AgyCliStatus
import com.foundryos.app.data.AgyModelInfo
import com.foundryos.app.data.AgyQuotaBucket
import com.foundryos.app.data.AgyQuotaGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Supervises the native on-device Antigravity CLI (`agy --hub` + `agy -p --output-format stream-json`),
 * captures Google OAuth URLs for the in-app Browser, queries live models & quota buckets via ConnectRPC JSON,
 * and coordinates multi-account switching.
 */
class AgyRuntimeManager(private val context: Context) {
    val installer = AgyCliInstaller(context)
    val accounts = AgyAccountManager(installer.homeDir())

    private val prefs: SharedPreferences = context.getSharedPreferences("ceo_agy_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val hubPort = 13235
    private val hubBaseUrl = "http://127.0.0.1:$hubPort"
    private val loginUrlRegex = Regex("""https://accounts\.google\.com/[^\s"'<>]+""")
    private val csrfPattern = Regex("""csrfToken["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    @Volatile private var hubProcess: Process? = null
    @Volatile private var activeRunProcess: Process? = null
    @Volatile private var csrfToken: String = ""
    @Volatile private var hubStatus: String = "stopped"
    @Volatile private var lastError: String = ""
    @Volatile private var cachedVersion: String = ""
    private val recentLogs = ArrayDeque<String>(60)
    private var loginPollJob: Job? = null

    private val _loginUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val loginUrlEvents: SharedFlow<String> = _loginUrlEvents.asSharedFlow()

    private val _stateTick = MutableStateFlow(0L)
    val stateTick: StateFlow<Long> = _stateTick.asStateFlow()

    var selectedModel: String
        get() = prefs.getString("agy_selected_model", "") ?: ""
        set(value) = prefs.edit().putString("agy_selected_model", value.trim()).apply()

    var preferAgyEngine: Boolean
        get() = prefs.getBoolean("agy_prefer_engine", true)
        set(value) = prefs.edit().putBoolean("agy_prefer_engine", value).apply()

    private fun emitTick() {
        _stateTick.value = System.currentTimeMillis()
    }

    private fun addLog(line: String) {
        synchronized(recentLogs) {
            if (recentLogs.size >= 60) recentLogs.removeFirst()
            recentLogs.addLast(line)
        }
        if (line.contains("accounts.google.com")) {
            loginUrlRegex.find(line)?.value?.let { url ->
                _loginUrlEvents.tryEmit(url)
            }
        }
    }

    suspend fun getCliStatus(): AgyCliStatus = withContext(Dispatchers.IO) {
        val installed = installer.isInstalled()
        if (installed && cachedVersion.isBlank()) {
            cachedVersion = installer.getInstalledVersion()
        }
        AgyCliStatus(
            supportedAbi = installer.isArm64Device(),
            installed = installed,
            version = cachedVersion,
            binaryPath = installer.agyBinary().absolutePath,
            hubStatus = if (!installed) "stopped" else if (isHubPortOpen()) "online" else hubStatus,
            lastError = lastError,
        )
    }

    private fun isHubPortOpen(): Boolean {
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress("127.0.0.1", hubPort), 350)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchCsrfToken(): String {
        return try {
            val req = Request.Builder().url("$hubBaseUrl/").get().build()
            httpClient.newCall(req).execute().use { resp ->
                val headerTok = resp.header("x-codeium-csrf-token")?.trim().orEmpty()
                if (headerTok.isNotBlank()) return headerTok
                val body = resp.body?.string().orEmpty()
                csrfPattern.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun ensureHubRunning(sandboxesDir: File): Boolean = withContext(Dispatchers.IO) {
        if (!installer.isInstalled()) return@withContext false
        if (isHubPortOpen()) {
            if (csrfToken.isBlank()) csrfToken = fetchCsrfToken()
            hubStatus = "online"
            return@withContext true
        }

        stopHub()
        hubStatus = "starting"
        lastError = ""
        emitTick()

        sandboxesDir.mkdirs()
        val home = installer.homeDir()
        home.mkdirs()

        val cmd = listOf(
            installer.agyBinary().absolutePath,
            "--hub",
            "--hub-port=$hubPort",
            "--app_data_dir=antigravity-cli",
            "--add-dir=${sandboxesDir.absolutePath}",
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(home)
        pb.environment().apply {
            put("HOME", home.absolutePath)
            put("USERPROFILE", home.absolutePath)
            put("AGY_ENABLE_HUB", "1")
            put("ANTIGRAVITY_VSCODE_HOST", "1")
            put("ANTIGRAVITY_AUTH_SUCCESS_APP", "vscode")
            put("CI", "1")
            put("AGY_NO_UPDATE_PROMPT", "1")
            put("AGY_CLI_DISABLE_AUTO_UPDATE", "1")
        }

        return@withContext try {
            val proc = pb.start()
            hubProcess = proc
            scope.launch {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { addLog(it) }
                }
            }
            scope.launch {
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine { addLog(it) }
                }
            }
            scope.launch {
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (hubProcess === proc) {
                    hubStatus = if (code == 0) "stopped" else "error"
                    if (code != 0) lastError = "agy hub exited ($code)"
                    emitTick()
                }
            }

            val deadline = System.currentTimeMillis() + 20_000L
            while (System.currentTimeMillis() < deadline) {
                if (isHubPortOpen()) {
                    csrfToken = fetchCsrfToken()
                    hubStatus = "online"
                    emitTick()
                    return@withContext true
                }
                if (!proc.isAlive) break
                delay(400)
            }
            false
        } catch (e: Exception) {
            hubStatus = "error"
            lastError = e.message ?: "Failed to start agy hub"
            emitTick()
            false
        }
    }

    fun stopHub() {
        loginPollJob?.cancel()
        runCatching { hubProcess?.destroyForcibly() }
        hubProcess = null
        csrfToken = ""
        hubStatus = "stopped"
    }

    fun stopActiveRun() {
        runCatching { activeRunProcess?.destroyForcibly() }
        activeRunProcess = null
    }

    private fun rpcPost(method: String, payload: JSONObject = JSONObject()): JSONObject? {
        if (!isHubPortOpen()) return null
        if (csrfToken.isBlank()) csrfToken = fetchCsrfToken()

        val req = Request.Builder()
            .url("$hubBaseUrl/exa.language_server_pb.LanguageServerService/$method")
            .header("Content-Type", "application/json")
            .header("Connect-Protocol-Version", "1")
            .apply { if (csrfToken.isNotBlank()) header("x-codeium-csrf-token", csrfToken) }
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    csrfToken = fetchCsrfToken()
                }
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || raw.isBlank()) null else JSONObject(raw)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getAuthState(sandboxesDir: File): AgyAuthState = withContext(Dispatchers.IO) {
        accounts.syncLiveIfKnown()
        val saved = accounts.listSavedAccounts()
        val localEmail = accounts.liveEmail()
        val hasLocalToken = accounts.readLiveToken() != null

        var signedIn = hasLocalToken
        var email = localEmail
        var fullName = ""
        var planName = ""
        var userTier = ""

        if (installer.isInstalled() && ensureHubRunning(sandboxesDir)) {
            val authResp = rpcPost("GetAuthStatus")
            val authResult = authResp?.optJSONObject("authResult") ?: authResp?.optJSONObject("auth_result")
            if (authResult != null) {
                signedIn = authResult.optBoolean("hasValidAuth", authResult.optBoolean("has_valid_auth", hasLocalToken))
            }
            if (signedIn) {
                val userResp = rpcPost("GetUserStatus", JSONObject().put("metadata", JSONObject()))
                val userStatus = userResp?.optJSONObject("userStatus") ?: userResp?.optJSONObject("user_status")
                if (userStatus != null) {
                    email = userStatus.optString("email").takeIf { it.isNotBlank() && it != "null" } ?: email
                    fullName = userStatus.optString("name").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                    val tierObj = userStatus.optJSONObject("userTier") ?: userStatus.optJSONObject("user_tier")
                    userTier = tierObj?.optString("name").orEmpty().ifBlank { tierObj?.optString("description").orEmpty() }
                    val planStatus = userStatus.optJSONObject("planStatus") ?: userStatus.optJSONObject("plan_status")
                    val planInfo = planStatus?.optJSONObject("planInfo")
                        ?: planStatus?.optJSONObject("plan_info")
                        ?: userStatus.optJSONObject("planInfo")
                        ?: userResp?.optJSONObject("planInfo")
                    planName = planInfo?.optString("planName").orEmpty().ifBlank { planInfo?.optString("plan_name").orEmpty() }
                }
                // Auto-save newly signed-in account if not saved yet
                if (hasLocalToken && !accounts.isLiveSaved() && email.isNotBlank()) {
                    accounts.saveCurrentAccount(email)
                }
            }
        }

        AgyAuthState(
            signedIn = signedIn,
            email = email,
            fullName = fullName,
            planName = planName,
            userTier = userTier,
            currentSaved = accounts.isLiveSaved(),
            accounts = accounts.listSavedAccounts().ifEmpty { saved },
        )
    }

    suspend fun startGoogleLogin(
        sandboxesDir: File,
        onSignedIn: suspend () -> Unit = {},
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!installer.isInstalled()) {
            return@withContext false to "Install Antigravity CLI first."
        }
        if (!ensureHubRunning(sandboxesDir)) {
            return@withContext false to "Could not start agy hub."
        }

        // Trigger Login RPC on agy --hub (emits https://accounts.google.com/... on stdout/stderr)
        scope.launch {
            rpcPost("Login", JSONObject().put("isGcpTos", false))
        }

        loginPollJob?.cancel()
        loginPollJob = scope.launch {
            val deadline = System.currentTimeMillis() + 240_000L
            while (System.currentTimeMillis() < deadline) {
                delay(1000)
                val auth = rpcPost("GetAuthStatus")
                val res = auth?.optJSONObject("authResult") ?: auth?.optJSONObject("auth_result")
                val ok = res?.optBoolean("hasValidAuth", res.optBoolean("has_valid_auth", false)) == true
                if (ok || accounts.readLiveToken() != null) {
                    val state = getAuthState(sandboxesDir)
                    if (state.signedIn) {
                        if (!accounts.isLiveSaved()) {
                            accounts.saveCurrentAccount(state.email)
                        }
                        emitTick()
                        onSignedIn()
                        break
                    }
                }
            }
        }
        true to "Waiting for Google sign-in..."
    }

    suspend fun switchGoogleAccount(email: String, sandboxesDir: File): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (ok, msg) = accounts.switchToAccount(email)
        if (!ok) return@withContext false to msg
        stopHub()
        ensureHubRunning(sandboxesDir)
        emitTick()
        true to msg
    }

    suspend fun addAnotherGoogleAccount(sandboxesDir: File, onSignedIn: suspend () -> Unit = {}): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            accounts.prepareForAnotherGoogleAccount()
            stopHub()
            startGoogleLogin(sandboxesDir, onSignedIn)
        }

    suspend fun fetchModelsAndQuotas(sandboxesDir: File): Pair<List<AgyModelInfo>, List<AgyQuotaGroup>> =
        withContext(Dispatchers.IO) {
            if (!installer.isInstalled() || !ensureHubRunning(sandboxesDir)) {
                return@withContext emptyList<AgyModelInfo>() to emptyList()
            }

            val modelsList = mutableListOf<AgyModelInfo>()
            val modelsResp = rpcPost("GetAvailableModels", JSONObject().put("forceRefresh", true))
            val inner = modelsResp?.optJSONObject("response")
            if (inner != null) {
                val modelsMap = inner.optJSONObject("models") ?: JSONObject()
                val sorts = inner.optJSONArray("agentModelSorts") ?: inner.optJSONArray("agent_model_sorts") ?: JSONArray()
                val orderedKeys = mutableListOf<String>()
                for (i in 0 until sorts.length()) {
                    val groups = sorts.optJSONObject(i)?.optJSONArray("groups") ?: JSONArray()
                    for (g in 0 until groups.length()) {
                        val ids = groups.optJSONObject(g)?.optJSONArray("modelIds")
                            ?: groups.optJSONObject(g)?.optJSONArray("model_ids") ?: JSONArray()
                        for (m in 0 until ids.length()) {
                            val k = ids.optString(m)
                            if (k.isNotBlank() && k !in orderedKeys) orderedKeys.add(k)
                        }
                    }
                }
                if (orderedKeys.isEmpty()) {
                    val it = modelsMap.keys()
                    while (it.hasNext()) orderedKeys.add(it.next())
                }

                for (key in orderedKeys) {
                    val details = modelsMap.optJSONObject(key) ?: continue
                    if (details.optBoolean("disabled", false)) continue
                    if (details.optBoolean("isInternal", details.optBoolean("is_internal", false))) continue
                    val displayName = details.optString("displayName").ifBlank { details.optString("display_name") }.ifBlank { key }
                    val qObj = details.optJSONObject("quotaInfo") ?: details.optJSONObject("quota_info")
                    val remFrac = qObj?.let {
                        if (it.has("remainingFraction")) it.optDouble("remainingFraction", 1.0).toFloat()
                        else if (it.has("remaining_fraction")) it.optDouble("remaining_fraction", 1.0).toFloat()
                        else null
                    }
                    val resetStr = qObj?.optString("resetTime").orEmpty().ifBlank { qObj?.optString("reset_time").orEmpty() }
                    val modelEnum = details.optString("model").ifBlank { key }
                    modelsList.add(
                        AgyModelInfo(
                            id = key,
                            key = modelEnum,
                            displayName = displayName,
                            supportsThinking = details.optBoolean("supportsThinking", details.optBoolean("supports_thinking", false)),
                            remainingFraction = remFrac,
                            resetCountdown = formatCountdown(resetStr),
                        )
                    )
                }
            }

            // Fallback to `agy models` CLI if ConnectRPC models map wasn't ready
            if (modelsList.isEmpty()) {
                modelsList.addAll(fetchModelsViaCli())
            }

            if (selectedModel.isBlank() && modelsList.isNotEmpty()) {
                selectedModel = modelsList.firstOrNull { it.id.contains("flash", true) }?.id ?: modelsList.first().id
            }

            val quotaGroups = mutableListOf<AgyQuotaGroup>()
            val quotaResp = rpcPost("RetrieveUserQuotaSummary", JSONObject().put("forceRefresh", true))
            val qInner = quotaResp?.optJSONObject("response")
            val groupsArr = qInner?.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until groupsArr.length()) {
                val grp = groupsArr.optJSONObject(i) ?: continue
                val gName = grp.optString("displayName").ifBlank { grp.optString("display_name") }.ifBlank { "Quota" }
                val buckets = grp.optJSONArray("buckets") ?: JSONArray()
                var fiveH: AgyQuotaBucket? = null
                var weekly: AgyQuotaBucket? = null
                for (bIdx in 0 until buckets.length()) {
                    val b = buckets.optJSONObject(bIdx) ?: continue
                    val window = b.optString("window")
                    val frac = (if (b.has("remainingFraction")) b.optDouble("remainingFraction", 1.0)
                    else b.optDouble("remaining_fraction", 1.0)).toFloat().coerceIn(0f, 1f)
                    val pct = String.format(Locale.US, "%.0f%%", frac * 100f)
                    val resetIso = b.optString("resetTime").ifBlank { b.optString("reset_time") }
                    val bucket = AgyQuotaBucket(
                        window = window,
                        displayName = b.optString("displayName").ifBlank { b.optString("display_name") }.ifBlank { window },
                        remainingFraction = frac,
                        remainingPct = pct,
                        countdown = formatCountdown(resetIso),
                    )
                    if (window.contains("5h", ignoreCase = true)) fiveH = bucket
                    else if (window.contains("week", ignoreCase = true) || window.contains("7d", ignoreCase = true)) weekly = bucket
                }
                quotaGroups.add(AgyQuotaGroup(groupName = gName, fiveHour = fiveH, weekly = weekly))
            }

            modelsList to quotaGroups
        }

    private fun fetchModelsViaCli(): List<AgyModelInfo> {
        return runCatching {
            val pb = ProcessBuilder(installer.agyBinary().absolutePath, "models")
            pb.environment()["HOME"] = installer.homeDir().absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(15, TimeUnit.SECONDS)
            out.lines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0].isNotBlank()) {
                    AgyModelInfo(id = parts[0].trim(), key = parts[0].trim(), displayName = parts[1].trim())
                } else null
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Executes a prompt with native Antigravity CLI inside the company's isolated sandbox directory (`--add-dir`).
     */
    suspend fun runPrompt(
        sandboxDir: File,
        prompt: String,
        modelId: String = selectedModel,
        onStatus: suspend (String) -> Unit = {},
    ): Pair<Boolean, String> = runMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!installer.isInstalled()) return@withContext false to "Antigravity CLI not installed"
            if (accounts.readLiveToken() == null) return@withContext false to "Not signed in to Google on Antigravity"

            sandboxDir.mkdirs()
            val cmd = mutableListOf(
                installer.agyBinary().absolutePath,
                "-p",
                prompt,
                "--output-format",
                "stream-json",
                "--mode",
                "auto",
                "--dangerously-skip-permissions",
                "--add-dir",
                sandboxDir.absolutePath,
            )
            val chosenModel = modelId.ifBlank { selectedModel }
            if (chosenModel.isNotBlank() && chosenModel != "auto") {
                cmd.add("--model")
                cmd.add(chosenModel)
            }

            val pb = ProcessBuilder(cmd)
            pb.directory(sandboxDir)
            pb.environment().apply {
                put("HOME", installer.homeDir().absolutePath)
                put("USERPROFILE", installer.homeDir().absolutePath)
                put("CI", "1")
                put("AGY_NO_UPDATE_PROMPT", "1")
                put("AGY_CLI_DISABLE_AUTO_UPDATE", "1")
            }

            val finalText = StringBuilder()
            var lastAssistantMsg = ""
            try {
                val proc = pb.start()
                activeRunProcess = proc
                val errFuture = scope.launch {
                    runCatching { proc.errorStream.bufferedReader().readText() }
                }

                proc.inputStream.bufferedReader().forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isBlank()) return@forEachLine
                    val obj = runCatching { JSONObject(line) }.getOrNull()
                    if (obj != null) {
                        val type = obj.optString("type").lowercase()
                        val text = obj.optString("text").ifBlank { obj.optString("content") }
                            .ifBlank { obj.optString("result") }
                        when {
                            type.contains("tool") -> {
                                val toolName = obj.optString("name", obj.optString("tool", "working"))
                                scope.launch { onStatus("agy: $toolName...") }
                            }
                            type == "result" && text.isNotBlank() -> {
                                lastAssistantMsg = text
                            }
                            text.isNotBlank() -> {
                                finalText.append(text)
                                lastAssistantMsg = finalText.toString()
                            }
                        }
                    } else {
                        finalText.append(line).append('\n')
                    }
                }

                val code = proc.waitFor()
                errFuture.cancel()
                activeRunProcess = null
                val outStr = lastAssistantMsg.ifBlank { finalText.toString() }.trim()
                if (code == 0 || outStr.isNotBlank()) true to outStr
                else false to "agy exited with code $code"
            } catch (e: Exception) {
                activeRunProcess = null
                false to (e.message ?: "agy execution failed")
            }
        }
    }

    private fun formatCountdown(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching {
            val clean = iso.substringBefore('.').removeSuffix("Z")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val target = sdf.parse(clean)?.time ?: return ""
            val diff = target - System.currentTimeMillis()
            if (diff <= 0) return "Resetting soon"
            val hours = (diff / (1000 * 60 * 60)).toInt()
            val mins = ((diff % (1000 * 60 * 60)) / (1000 * 60)).toInt()
            if (hours > 0) "Resets in ${hours}h ${mins}m" else "Resets in ${mins}m"
        }.getOrDefault("")
    }
}
