package com.foundryos.app.engine

import android.content.Context
import com.foundryos.app.data.ProviderStatus
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

class OnDeviceLLMClient(context: Context) {
    private val prefs = context.getSharedPreferences("ceo_llm_keys", Context.MODE_PRIVATE)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        val SUPPORTED_PROVIDERS = listOf("openrouter", "gemini", "xai", "openai", "anthropic", "deepseek", "groq")

        val DEFAULT_MODELS = mapOf(
            "openrouter" to "anthropic/claude-3.5-sonnet",
            "gemini" to "gemini-2.5-flash",
            "xai" to "grok-3-mini",
            "openai" to "gpt-4o-mini",
            "anthropic" to "claude-3-5-sonnet-latest",
            "deepseek" to "deepseek-chat",
            "groq" to "llama-3.3-70b-versatile",
        )

        val ENDPOINTS = mapOf(
            "openrouter" to "https://openrouter.ai/api/v1/chat/completions",
            "gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "xai" to "https://api.x.ai/v1/chat/completions",
            "openai" to "https://api.openai.com/v1/chat/completions",
            "anthropic" to "https://api.anthropic.com/v1/messages",
            "deepseek" to "https://api.deepseek.com/v1/chat/completions",
            "groq" to "https://api.groq.com/openai/v1/chat/completions",
        )
    }

    fun saveProviderKey(provider: String, apiKey: String, model: String, customUrl: String = "") {
        val p = provider.lowercase().trim()
        prefs.edit()
            .putString("key_$p", apiKey.trim())
            .putString("model_$p", model.trim().ifBlank { DEFAULT_MODELS[p] ?: "" })
            .apply {
                if (customUrl.isNotBlank()) putString("url_$p", customUrl.trim())
            }
            .apply()
    }

    fun clearProviderKey(provider: String) {
        val p = provider.lowercase().trim()
        prefs.edit().remove("key_$p").apply()
    }

    fun getProviderKey(provider: String): String = prefs.getString("key_${provider.lowercase()}", "") ?: ""

    fun getProviderModel(provider: String): String {
        val p = provider.lowercase()
        return prefs.getString("model_$p", DEFAULT_MODELS[p] ?: "") ?: (DEFAULT_MODELS[p] ?: "")
    }

    fun getProviderStatuses(): List<ProviderStatus> = SUPPORTED_PROVIDERS.map { p ->
        ProviderStatus(
            name = p,
            configured = getProviderKey(p).isNotBlank(),
            model = getProviderModel(p),
            baseUrl = prefs.getString("url_$p", ENDPOINTS[p] ?: "") ?: (ENDPOINTS[p] ?: ""),
        )
    }

    fun activeProviderSummary(): String {
        val active = SUPPORTED_PROVIDERS.filter { getProviderKey(it).isNotBlank() }
        return if (active.isEmpty()) {
            "On-Device Engine · Add an LLM API key"
        } else {
            "On-Device Engine · ${active.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }} ✓"
        }
    }

    fun hasAnyKey(): Boolean = SUPPORTED_PROVIDERS.any { getProviderKey(it).isNotBlank() }

    suspend fun testProvider(provider: String): Pair<Boolean, String> {
        val p = provider.lowercase().trim()
        val key = getProviderKey(p)
        if (key.isBlank()) return false to "No API key saved for $p"
        val model = getProviderModel(p)
        val res = callSingleProvider(
            provider = p,
            model = model,
            apiKey = key,
            messages = listOf(
                "system" to "Reply with OK.",
                "user" to "Ping",
            ),
            jsonMode = false,
            maxTokens = 16,
        )
        return if (res.ok) true to "$p ($model) verified ✓"
        else false to (res.error.ifBlank { "Request failed" })
    }

    suspend fun complete(
        messages: List<Pair<String, String>>,
        modelSpec: String = "auto",
        jsonMode: Boolean = false,
        maxTokens: Int = 2400,
    ): LLMCallResult {
        val chain = buildFallbackChain(modelSpec)
        if (chain.isEmpty()) {
            return LLMCallResult(ok = false, isOffline = true, error = "No LLM API key configured")
        }
        var lastError = ""
        for ((prov, mod, key) in chain) {
            val res = callSingleProvider(prov, mod, key, messages, jsonMode, maxTokens)
            if (res.ok && res.content.isNotBlank()) return res
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
            if (key.isNotBlank()) out.add(Triple(prov, mod, key))
        }
        for (prov in SUPPORTED_PROVIDERS) {
            val key = getProviderKey(prov)
            if (key.isNotBlank() && out.none { it.first == prov }) {
                out.add(Triple(prov, getProviderModel(prov), key))
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
            if (provider == "anthropic") {
                callAnthropic(model, apiKey, messages, maxTokens)
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
        val url = prefs.getString("url_$provider", ENDPOINTS[provider]) ?: ENDPOINTS[provider].orEmpty()
        val msgArray = JSONArray()
        for ((role, content) in messages) {
            msgArray.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", msgArray)
            .put("temperature", 0.3)
            .put("max_tokens", maxTokens)
        if (jsonMode && provider in listOf("openai", "openrouter", "deepseek", "groq", "gemini")) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
        if (provider == "openrouter") {
            reqBuilder.header("HTTP-Referer", "https://github.com/Anisghd-lab/ceo-app")
            reqBuilder.header("X-Title", "CEO App")
        }

        client.newCall(reqBuilder.post(body.toString().toRequestBody(jsonMedia)).build()).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMCallResult(ok = false, provider = provider, model = model, error = "HTTP ${resp.code}: ${raw.take(220)}")
            }
            val json = JSONObject(raw)
            val text = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            val tokens = json.optJSONObject("usage")?.optInt("total_tokens", text.length / 4) ?: (text.length / 4)
            return LLMCallResult(ok = true, content = text, provider = provider, model = model, tokensUsed = tokens)
        }
    }

    private fun callAnthropic(
        model: String,
        apiKey: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
    ): LLMCallResult {
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
            .url(ENDPOINTS["anthropic"]!!)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                return LLMCallResult(ok = false, provider = "anthropic", model = model, error = "HTTP ${resp.code}: ${raw.take(220)}")
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
            return LLMCallResult(ok = true, content = sb.toString(), provider = "anthropic", model = model, tokensUsed = tokens)
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
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end > start) {
            try { return JSONObject(trimmed.substring(start, end + 1)) } catch (_: Exception) {}
        }
        return null
    }
}
