package com.foundryos.app.engine

import android.content.Context
import com.foundryos.app.data.ProviderStatus
import com.foundryos.app.data.RemoteModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LLMCallResult(
    val ok: Boolean,
    val content: String = "",
    val provider: String = "offline",
    val model: String = "",
    val tokensUsed: Int = 0,
    val error: String = "",
    val isOffline: Boolean = false,
)

data class ModelFetchResult(
    val ok: Boolean,
    val models: List<RemoteModelInfo> = emptyList(),
    val message: String = "",
)

data class CustomProviderDef(
    val id: String,
    val baseUrl: String,
    val protocol: String = "openai", // "openai" | "anthropic"
)

class OnDeviceLLMClient(context: Context) {
    private val prefs = context.getSharedPreferences("ceo_llm_keys", Context.MODE_PRIVATE)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_FREELLMAPI_URL = "http://127.0.0.1:3001"
        val BUILTIN_PROVIDERS = listOf("freellmapi", "openrouter", "gemini", "xai", "openai", "anthropic", "deepseek", "groq")

        val ENDPOINTS = mapOf(
            "freellmapi" to "$DEFAULT_FREELLMAPI_URL/v1/chat/completions",
            "openrouter" to "https://openrouter.ai/api/v1/chat/completions",
            "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "xai" to "https://api.x.ai/v1/chat/completions",
            "openai" to "https://api.openai.com/v1/chat/completions",
            "anthropic" to "https://api.anthropic.com/v1/messages",
            "deepseek" to "https://api.deepseek.com/v1/chat/completions",
            "groq" to "https://api.groq.com/openai/v1/chat/completions",
        )

        val MODELS_ENDPOINTS = mapOf(
            "freellmapi" to "$DEFAULT_FREELLMAPI_URL/v1/models",
            "openrouter" to "https://openrouter.ai/api/v1/models",
            "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/models",
            "xai" to "https://api.x.ai/v1/models",
            "openai" to "https://api.openai.com/v1/models",
            "anthropic" to "https://api.anthropic.com/v1/models",
            "deepseek" to "https://api.deepseek.com/models",
            "groq" to "https://api.groq.com/openai/v1/models",
        )
    }

    fun getCustomProviders(): List<CustomProviderDef> {
        val raw = prefs.getString("custom_providers_list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim().lowercase()
                val baseUrl = o.optString("base_url").trim()
                if (id.isBlank() || baseUrl.isBlank()) null
                else CustomProviderDef(id, baseUrl, o.optString("protocol", "openai"))
            }
        }.getOrDefault(emptyList())
    }

    fun addCustomProvider(name: String, baseUrl: String, protocol: String = "openai"): String {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
            .ifBlank { "custom-${System.currentTimeMillis() % 10000}" }
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanProto = if (protocol.equals("anthropic", ignoreCase = true)) "anthropic" else "openai"
        val current = getCustomProviders().filterNot { it.id == slug }.toMutableList()
        current.add(CustomProviderDef(slug, cleanUrl, cleanProto))
        saveCustomProviders(current)
        return slug
    }

    fun deleteCustomProvider(providerId: String) {
        val slug = providerId.trim().lowercase()
        val updated = getCustomProviders().filterNot { it.id == slug }
        saveCustomProviders(updated)
        clearProviderKey(slug)
    }

    private fun saveCustomProviders(list: List<CustomProviderDef>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("base_url", item.baseUrl)
                    .put("protocol", item.protocol)
            )
        }
        prefs.edit().putString("custom_providers_list", arr.toString()).apply()
    }

    private fun allProviderIds(): List<String> = BUILTIN_PROVIDERS + getCustomProviders().map { it.id }

    private fun findCustom(providerId: String): CustomProviderDef? =
        getCustomProviders().firstOrNull { it.id == providerId.lowercase().trim() }

    private fun resolveProtocol(providerId: String): String {
        val p = providerId.lowercase().trim()
        if (p == "anthropic") return "anthropic"
        return findCustom(p)?.protocol ?: "openai"
    }

    fun getProviderBaseUrl(provider: String): String {
        val p = provider.lowercase().trim()
        if (p == "freellmapi") {
            return prefs.getString("base_url_freellmapi", DEFAULT_FREELLMAPI_URL)?.trim()?.ifBlank { DEFAULT_FREELLMAPI_URL }
                ?: DEFAULT_FREELLMAPI_URL
        }
        return findCustom(p)?.baseUrl ?: ENDPOINTS[p].orEmpty()
    }

    fun saveProviderBaseUrl(provider: String, baseUrl: String) {
        val p = provider.lowercase().trim()
        val clean = baseUrl.trim().trimEnd('/')
        if (p == "freellmapi" && clean.startsWith("http")) {
            prefs.edit().putString("base_url_freellmapi", clean).apply()
        }
    }

    private fun resolveFreeLlmEndpoint(suffix: String): String {
        val raw = getProviderBaseUrl("freellmapi").trimEnd('/')
        val root = when {
            raw.endsWith("/v1/chat/completions") -> raw.removeSuffix("/v1/chat/completions")
            raw.endsWith("/v1/models") -> raw.removeSuffix("/v1/models")
            raw.endsWith("/v1") -> raw.removeSuffix("/v1")
            else -> raw
        }
        return "$root/v1/$suffix"
    }

    private fun resolveChatUrl(providerId: String): String {
        val p = providerId.lowercase().trim()
        if (p == "freellmapi") return resolveFreeLlmEndpoint("chat/completions")
        ENDPOINTS[p]?.let { return it }
        val custom = findCustom(p) ?: return ""
        val base = custom.baseUrl.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") || base.endsWith("/messages") -> base
            custom.protocol == "anthropic" -> "$base/messages"
            else -> "$base/chat/completions"
        }
    }

    private fun resolveModelsUrl(providerId: String): String {
        val p = providerId.lowercase().trim()
        if (p == "freellmapi") return resolveFreeLlmEndpoint("models")
        MODELS_ENDPOINTS[p]?.let { return it }
        val custom = findCustom(p) ?: return ""
        val base = custom.baseUrl.trimEnd('/')
        return when {
            base.endsWith("/models") -> base
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
            base.endsWith("/messages") -> base.removeSuffix("/messages") + "/models"
            else -> "$base/models"
        }
    }

    fun saveProviderKey(provider: String, apiKey: String) {
        val p = provider.lowercase().trim()
        prefs.edit().putString("key_$p", apiKey.trim()).apply()
    }

    fun saveProviderModel(provider: String, model: String) {
        val p = provider.lowercase().trim()
        prefs.edit().putString("model_$p", model.trim()).apply()
    }

    fun clearProviderKey(provider: String) {
        val p = provider.lowercase().trim()
        prefs.edit().remove("key_$p").remove("model_$p").remove("models_cache_$p").apply()
    }

    fun getProviderKey(provider: String): String = prefs.getString("key_${provider.lowercase()}", "") ?: ""

    fun getProviderModel(provider: String): String = prefs.getString("model_${provider.lowercase()}", "") ?: ""

    fun getCachedModels(provider: String): List<RemoteModelInfo> {
        val raw = prefs.getString("models_cache_${provider.lowercase()}", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                RemoteModelInfo(
                    id = o.optString("id"),
                    name = o.optString("name", o.optString("id")),
                    isFree = o.optBoolean("free", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveCachedModels(provider: String, models: List<RemoteModelInfo>) {
        val arr = JSONArray()
        for (m in models) {
            arr.put(JSONObject().put("id", m.id).put("name", m.name).put("free", m.isFree))
        }
        prefs.edit().putString("models_cache_${provider.lowercase()}", arr.toString()).apply()
    }

    private fun canSkipApiKey(providerId: String): Boolean {
        val p = providerId.lowercase().trim()
        if (p == "freellmapi") return isLocalUrl(getProviderBaseUrl("freellmapi"))
        val custom = findCustom(p)
        return custom != null && isLocalUrl(custom.baseUrl)
    }

    fun getProviderStatuses(): List<ProviderStatus> {
        val builtIn = BUILTIN_PROVIDERS.map { p ->
            ProviderStatus(
                name = p,
                configured = getProviderModel(p).isNotBlank() && (getProviderKey(p).isNotBlank() || canSkipApiKey(p)),
                model = getProviderModel(p),
                baseUrl = if (p == "freellmapi") getProviderBaseUrl("freellmapi") else (ENDPOINTS[p] ?: ""),
                isCustom = false,
                protocol = if (p == "anthropic") "anthropic" else "openai",
            )
        }
        val custom = getCustomProviders().map { c ->
            ProviderStatus(
                name = c.id,
                configured = getProviderModel(c.id).isNotBlank() && (getProviderKey(c.id).isNotBlank() || isLocalUrl(c.baseUrl)),
                model = getProviderModel(c.id),
                baseUrl = c.baseUrl,
                isCustom = true,
                protocol = c.protocol,
            )
        }
        return builtIn + custom
    }

    private fun isLocalUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("127.0.0.1") || l.contains("localhost") || l.contains("192.168.") || l.contains("10.0.")
    }

    fun activeProviderSummary(): String {
        val statuses = getProviderStatuses()
        val active = statuses.filter { it.configured }.map { it.name }
        val keyOnly = statuses.filter { !it.configured && getProviderKey(it.name).isNotBlank() }.map { it.name }
        return when {
            active.isNotEmpty() -> "On-Device Engine · ${active.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }} ✓"
            keyOnly.isNotEmpty() -> "Key saved (${keyOnly.first()}) · Pick a model"
            else -> "On-Device Engine · Add an LLM API key"
        }
    }

    fun hasAnyKey(): Boolean = getProviderStatuses().any { it.configured }

    /**
     * Step 1: Query the provider's API first, verify credentials, and return the real available models.
     */
    suspend fun fetchAvailableModels(provider: String, apiKeyInput: String): ModelFetchResult = withContext(Dispatchers.IO) {
        val p = provider.lowercase().trim()
        val key = apiKeyInput.trim().ifBlank { getProviderKey(p) }
        if (key.isBlank() && !canSkipApiKey(p)) {
            return@withContext ModelFetchResult(false, emptyList(), "Please paste your $p API key first.")
        }

        if (key.isNotBlank()) saveProviderKey(p, key)

        try {
            if (p == "openrouter") {
                val authReq = Request.Builder()
                    .url("https://openrouter.ai/api/v1/auth/key")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
                client.newCall(authReq).execute().use { authResp ->
                    val authBody = authResp.body?.string() ?: ""
                    if (!authResp.isSuccessful) {
                        return@withContext ModelFetchResult(
                            false,
                            emptyList(),
                            "OpenRouter key rejected (HTTP ${authResp.code}): ${authBody.take(180)}",
                        )
                    }
                }
            }

            val modelsUrl = resolveModelsUrl(p)
            if (modelsUrl.isBlank()) {
                return@withContext ModelFetchResult(false, emptyList(), "Missing endpoint URL for $p")
            }

            val reqBuilder = Request.Builder().url(modelsUrl).get()
            if (key.isNotBlank()) {
                if (resolveProtocol(p) == "anthropic") {
                    reqBuilder.header("x-api-key", key)
                    reqBuilder.header("anthropic-version", "2023-06-01")
                } else {
                    reqBuilder.header("Authorization", "Bearer $key")
                }
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext ModelFetchResult(
                        false,
                        emptyList(),
                        "API error HTTP ${resp.code}: ${raw.take(200)}",
                    )
                }

                val trimmed = raw.trim()
                val dataArr: JSONArray = when {
                    trimmed.startsWith("[") -> JSONArray(trimmed)
                    trimmed.startsWith("{") -> {
                        val root = JSONObject(trimmed)
                        root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                    }
                    else -> JSONArray()
                }

                val parsed = mutableListOf<RemoteModelInfo>()
                if (trimmed.startsWith("{")) {
                    val autoObj = JSONObject(trimmed).optJSONObject("auto")
                    if (autoObj != null) {
                        val aId = autoObj.optString("id").ifBlank { "auto" }
                        parsed.add(
                            RemoteModelInfo(
                                id = aId,
                                name = autoObj.optString("description").ifBlank { "Auto (Free Router)" },
                                isFree = true,
                            )
                        )
                    }
                }
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.opt(i)
                    if (item is String && item.isNotBlank()) {
                        parsed.add(RemoteModelInfo(id = item, name = item, isFree = (p == "freellmapi") || item.endsWith(":free")))
                        continue
                    }
                    val m = item as? JSONObject ?: continue
                    if (m.has("available") && !m.optBoolean("available", true)) continue
                    val id = m.optString("id").ifBlank { m.optString("name") }
                    if (id.isBlank()) continue
                    val displayName = m.optString("display_name").ifBlank { m.optString("name").ifBlank { id } }
                    val pricing = m.optJSONObject("pricing")
                    val isFree = (p == "freellmapi") ||
                        (pricing != null &&
                            pricing.optString("prompt") == "0" &&
                            pricing.optString("completion") == "0") ||
                        id.endsWith(":free")
                    parsed.add(RemoteModelInfo(id = id, name = displayName, isFree = isFree))
                }

                if (parsed.isEmpty()) {
                    return@withContext ModelFetchResult(false, emptyList(), "API responded OK but returned 0 available models.")
                }

                val sorted = parsed.distinctBy { it.id }.sortedWith(
                    compareBy<RemoteModelInfo> { if (it.id == "auto") 0 else if (it.id == "fusion") 1 else 2 }
                        .thenBy { !it.isFree }
                        .thenBy { it.id }
                )
                saveCachedModels(p, sorted)

                val currentModel = getProviderModel(p)
                if (currentModel.isNotBlank() && sorted.none { it.id == currentModel }) {
                    saveProviderModel(p, "")
                } else if (p == "freellmapi" && currentModel.isBlank()) {
                    val defaultFree = sorted.firstOrNull { it.id == "auto" } ?: sorted.firstOrNull { it.isFree }
                    if (defaultFree != null) saveProviderModel(p, defaultFree.id)
                }

                val freeCount = sorted.count { it.isFree }
                ModelFetchResult(
                    ok = true,
                    models = sorted,
                    message = if (p == "freellmapi") "✓ FreeLLMAPI verified · ${sorted.size} available free models loaded"
                    else "✓ Key verified · ${sorted.size} live models loaded from $p" + if (freeCount > 0) " ($freeCount free)" else "",
                )
            }
        } catch (e: Exception) {
            ModelFetchResult(false, emptyList(), "Network error: ${e.message ?: "unknown"}")
        }
    }

    /**
     * Step 2: Verify the chosen live model actually responds to a completion request.
     */
    suspend fun saveAndTestModel(provider: String, modelId: String): Pair<Boolean, String> {
        val p = provider.lowercase().trim()
        val cleanModel = modelId.trim()
        val key = getProviderKey(p)
        if (key.isBlank() && !canSkipApiKey(p)) {
            return false to "Verify your API key first."
        }
        if (cleanModel.isBlank()) return false to "Select or enter a model ID first."

        saveProviderModel(p, cleanModel)
        val res = callSingleProvider(
            provider = p,
            model = cleanModel,
            apiKey = key,
            messages = listOf(
                "system" to "Reply with OK.",
                "user" to "Ping",
            ),
            jsonMode = false,
            maxTokens = 16,
        )
        return if (res.ok) true to "$p · $cleanModel verified ✓"
        else {
            val hint = if (p == "freellmapi" && cleanModel != "auto" && (res.error.contains("404") || res.error.contains("503"))) {
                " · Tip: select 'auto' (Free Smart Router) at the top of the list."
            } else ""
            false to (res.error.ifBlank { "Model test failed" } + hint)
        }
    }

    suspend fun complete(
        messages: List<Pair<String, String>>,
        modelSpec: String = "auto",
        jsonMode: Boolean = false,
        maxTokens: Int = 2400,
    ): LLMCallResult {
        val chain = buildFallbackChain(modelSpec)
        if (chain.isEmpty()) {
            return LLMCallResult(ok = false, isOffline = true, error = "No verified LLM provider/model configured")
        }
        var lastError = ""
        for ((prov, mod, key) in chain) {
            val res = callSingleProvider(prov, mod, key, messages, jsonMode, maxTokens)
            if (res.ok && res.content.isNotBlank()) {
                if (jsonMode && extractJsonObject(res.content) == null) {
                    lastError = "${prov}/${mod}: non-JSON response"
                    continue
                }
                return res
            }
            lastError = "${prov}/${mod}: ${res.error}"
        }
        return LLMCallResult(ok = false, error = lastError, isOffline = false)
    }

    private fun buildFallbackChain(modelSpec: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        if (modelSpec.isNotBlank() && modelSpec != "auto" && ":" in modelSpec) {
            val parts = modelSpec.split(":", limit = 2)
            val prov = parts[0].trim().lowercase()
            val mod = parts[1].trim().ifBlank { getProviderModel(prov) }
            val key = getProviderKey(prov)
            if (mod.isNotBlank() && (key.isNotBlank() || canSkipApiKey(prov))) {
                out.add(Triple(prov, mod, key))
            }
        }
        for (prov in allProviderIds()) {
            val key = getProviderKey(prov)
            val mod = getProviderModel(prov)
            val hasCreds = key.isNotBlank() || canSkipApiKey(prov)
            if (hasCreds && mod.isNotBlank() && out.none { it.first == prov }) {
                out.add(Triple(prov, mod, key))
                // On FreeLLMAPI, automatically fail over from stale/rate-limited slots to the "auto" free router
                if (prov == "freellmapi" && !mod.equals("auto", ignoreCase = true)) {
                    out.add(Triple(prov, "auto", key))
                }
            }
        }
        return out
    }

    private suspend fun callSingleProvider(
        provider: String,
        model: String,
        apiKey: String,
        messages: List<Pair<String, String>>,
        jsonMode: Boolean,
        maxTokens: Int,
    ): LLMCallResult = withContext(Dispatchers.IO) {
        try {
            if (resolveProtocol(provider) == "anthropic") {
                callAnthropic(provider, model, apiKey, messages, maxTokens)
            } else {
                callOpenAiCompat(provider, model, apiKey, messages, jsonMode, maxTokens)
            }
        } catch (e: Exception) {
            LLMCallResult(ok = false, provider = provider, model = model, error = e.message ?: "Network error")
        }
    }

    private fun callOpenAiCompat(
        provider: String,
        model: String,
        apiKey: String,
        messages: List<Pair<String, String>>,
        jsonMode: Boolean,
        maxTokens: Int,
    ): LLMCallResult {
        val url = resolveChatUrl(provider)
        if (url.isBlank()) {
            return LLMCallResult(ok = false, provider = provider, model = model, error = "Missing endpoint URL")
        }
        val msgArray = JSONArray()
        for ((role, content) in messages) {
            msgArray.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgArray)
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens)
        // OpenRouter free-tier router (`openrouter/free` / `:free`) often returns content=null with response_format=json_object
        val useJsonObjectFormat = jsonMode &&
            provider in listOf("openai", "openrouter", "deepseek", "groq", "gemini") &&
            !(provider == "openrouter" && (model.contains(":free", true) || model.equals("openrouter/free", true)))
        if (useJsonObjectFormat) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        fun executeOnce(reqBody: JSONObject): LLMCallResult {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }
            if (provider == "openrouter") {
                reqBuilder.header("HTTP-Referer", "https://github.com/Anisghd-lab/ceo-app")
                reqBuilder.header("X-Title", "CEO App")
            }
            client.newCall(reqBuilder.post(reqBody.toString().toRequestBody(jsonMedia)).build()).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return LLMCallResult(ok = false, provider = provider, model = model, error = "HTTP ${resp.code}: ${raw.take(220)}")
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return LLMCallResult(ok = false, provider = provider, model = model, error = "Malformed JSON response from $provider")
                val errObj = json.optJSONObject("error")
                if (errObj != null) {
                    return LLMCallResult(ok = false, provider = provider, model = model, error = errObj.optString("message").ifBlank { errObj.toString().take(200) })
                }
                val choice0 = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: return LLMCallResult(ok = false, provider = provider, model = model, error = "Empty choices from $provider/$model")
                val msgObj = choice0.optJSONObject("message") ?: JSONObject()
                val text = extractOpenAiMessageText(msgObj)
                if (text.isBlank()) {
                    return LLMCallResult(ok = false, provider = provider, model = model, error = "Model $provider/$model returned empty/null content")
                }
                val tokens = json.optJSONObject("usage")?.optInt("total_tokens", text.length / 4) ?: (text.length / 4)
                return LLMCallResult(ok = true, content = text, provider = provider, model = model, tokensUsed = tokens)
            }
        }

        val first = executeOnce(body)
        if (!first.ok && useJsonObjectFormat) {
            body.remove("response_format")
            val retry = executeOnce(body)
            if (retry.ok) return retry
        }
        return first
    }

    private fun extractOpenAiMessageText(msgObj: JSONObject): String {
        val rawContent = if (msgObj.isNull("content")) null else msgObj.opt("content")
        val primary = when (rawContent) {
            is String -> rawContent.trim().takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until rawContent.length()) {
                    val part = rawContent.opt(i)
                    when (part) {
                        is String -> sb.append(part)
                        is JSONObject -> {
                            val t = if (part.isNull("text")) "" else part.optString("text")
                            if (t.isNotBlank() && !t.equals("null", ignoreCase = true)) sb.append(t)
                        }
                    }
                }
                sb.toString().trim()
            }
            else -> ""
        }
        if (primary.isNotBlank()) return primary

        // Some reasoning models (DeepSeek/Qwen/OpenRouter free) emit text in reasoning_content or reasoning when content is null
        for (key in listOf("reasoning_content", "reasoning", "text")) {
            if (!msgObj.isNull(key)) {
                val candidate = msgObj.optString(key).trim()
                if (candidate.isNotBlank() && !candidate.equals("null", ignoreCase = true)) {
                    // Prefer JSON block if present inside reasoning, else use reasoning text
                    val extracted = extractJsonObject(candidate)
                    return extracted?.toString() ?: candidate
                }
            }
        }
        return ""
    }

    private fun callAnthropic(
        provider: String,
        model: String,
        apiKey: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
    ): LLMCallResult {
        val url = resolveChatUrl(provider).ifBlank { ENDPOINTS["anthropic"]!! }
        val sys = messages.filter { it.first == "system" }.joinToString("\n\n") { it.second }
        val nonSys = JSONArray()
        for ((role, content) in messages.filter { it.first != "system" }) {
            nonSys.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.3)
            .put("messages", nonSys)
        if (sys.isNotBlank()) body.put("system", sys)

        val req = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMCallResult(ok = false, provider = provider, model = model, error = "HTTP ${resp.code}: ${raw.take(220)}")
            }
            val json = JSONObject(raw)
            val arr = json.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            val usage = json.optJSONObject("usage")
            val tokens = (usage?.optInt("input_tokens", 0) ?: 0) + (usage?.optInt("output_tokens", 0) ?: 0)
            return LLMCallResult(ok = true, content = sb.toString(), provider = provider, model = model, tokensUsed = tokens)
        }
    }

    fun extractJsonObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        val trimmed = raw.trim()
        if ("```" in trimmed) {
            val parts = trimmed.split("```")
            for (chunk in parts) {
                var c = chunk.trim()
                if (c.startsWith("json", ignoreCase = true)) c = c.substring(4).trim()
                if (c.startsWith("{") && c.endsWith("}")) {
                    try { return JSONObject(c) } catch (_: Exception) {}
                }
            }
        }
        // Scan balanced {...} blocks cleanly (respecting strings/escapes) so prose around JSON doesn't break parsing
        var depth = 0
        var startIdx = -1
        var inString = false
        var escaped = false
        for (i in trimmed.indices) {
            val ch = trimmed[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') {
                    if (depth == 0) startIdx = i
                    depth++
                } else if (ch == '}' && depth > 0) {
                    depth--
                    if (depth == 0 && startIdx != -1) {
                        val candidate = trimmed.substring(startIdx, i + 1)
                        try { return JSONObject(candidate) } catch (_: Exception) {}
                        startIdx = -1
                    }
                }
            }
        }
        return null
    }

    /**
     * Parses BOTH standard JSON action objects AND XML-style tool calls emitted by Qwen3 / Hermes models
     * (e.g. `<tool_call><function=browser_search><parameter=query>...</parameter></function></tool_call>`).
     */
    fun extractActionOrToolCall(raw: String): JSONObject? {
        val xmlCalls = extractXmlToolCalls(raw)
        if (xmlCalls != null) return xmlCalls
        return extractJsonObject(raw)
    }

    fun extractXmlToolCalls(raw: String): JSONObject? {
        if (!raw.contains("<tool_call", ignoreCase = true) && !raw.contains("<function=", ignoreCase = true)) {
            return null
        }
        val calls = JSONArray()

        // Format A: Qwen3 <function=tool_name><parameter=key>value</parameter></function>
        val fnRegex = Regex(
            """<function\s*=\s*([a-zA-Z0-9_.-]+)\s*>(.*?)</function>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val paramRegex = Regex(
            """<parameter\s*=\s*([a-zA-Z0-9_.-]+)\s*>(.*?)</parameter>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        for (m in fnRegex.findAll(raw)) {
            val toolName = m.groupValues[1].trim()
            if (toolName.isBlank()) continue
            val body = m.groupValues[2]
            val args = JSONObject()
            for (pm in paramRegex.findAll(body)) {
                val pKey = pm.groupValues[1].trim()
                val pVal = pm.groupValues[2].trim()
                if (pKey.isNotBlank()) {
                    val parsedVal: Any = when {
                        pVal.startsWith("{") && pVal.endsWith("}") -> runCatching { JSONObject(pVal) }.getOrDefault(pVal)
                        pVal.startsWith("[") && pVal.endsWith("]") -> runCatching { JSONArray(pVal) }.getOrDefault(pVal)
                        else -> pVal
                    }
                    args.put(pKey, parsedVal)
                }
            }
            calls.put(JSONObject().put("tool", toolName).put("args", args))
        }

        // Format B: <tool_call>{"name":"...", "arguments":{...}}</tool_call>
        if (calls.length() == 0) {
            val tcRegex = Regex(
                """<tool_call>(.*?)</tool_call>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
            )
            for (m in tcRegex.findAll(raw)) {
                val obj = extractJsonObject(m.groupValues[1]) ?: continue
                val tName = obj.optString("tool").ifBlank { obj.optString("name") }.trim()
                if (tName.isBlank()) continue
                val args = obj.optJSONObject("args") ?: obj.optJSONObject("arguments") ?: JSONObject()
                calls.put(JSONObject().put("tool", tName).put("args", args))
            }
        }

        if (calls.length() == 0) return null
        return JSONObject().put("action", "tools").put("calls", calls)
    }

    /**
     * Strips stray model markup (`<think>...</think>`, `<tool_call>...</tool_call>`) so raw internal tags
     * never leak into user-facing chat bubbles.
     */
    fun sanitizeAssistantDisplayText(raw: String): String {
        return raw
            .replace(Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<tool_call>.*?</tool_call>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .replace(Regex("""<function\s*=[^>]+>.*?</function>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
            .trim()
    }
}
