package com.foundryos.app.plugins

import android.content.Context
import android.webkit.CookieManager
import com.foundryos.app.data.PluginAuthType
import com.foundryos.app.data.PluginToolResult
import com.foundryos.app.engine.OnDeviceSandbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Executes structured plugin tools for CEO and all specialist bots directly on Android:
 * - Native Browser search/read (inheriting authenticated CEO Browser cookies)
 * - Notion (MCP & REST), GitHub REST, Slack Web API, Gmail REST, Google Drive REST,
 *   Figma MCP/REST, Canva Connect, Kittl web session, Product Design prototype builder,
 *   Scite scientific citations, and TinyFish extraction.
 * - External Write actions return `requiresApproval = true` until Human taps "Ship".
 */
class PluginToolExecutor(
    private val context: Context,
    private val auth: PluginAuthManager,
    private val sandbox: OnDeviceSandbox,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Test hooks (unused in production) so unit tests can mock REST & WebView reads deterministically
    internal var apiBaseOverrides: MutableMap<String, String> = mutableMapOf()
    internal var sessionReaderForTest: (suspend (String) -> WebViewReadResult)? = null
    private val webViewReader by lazy { BrowserSessionReader(context.applicationContext) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun baseUrl(key: String, defaultBase: String): String =
        (apiBaseOverrides[key] ?: defaultBase).trimEnd('/')

    private fun collectCookiesForUrl(targetUrl: String): String {
        return runCatching {
            val cm = CookieManager.getInstance()
            val u = java.net.URI(targetUrl)
            val host = u.host.orEmpty()
            val scheme = u.scheme ?: "https"
            val candidates = mutableSetOf(targetUrl)
            if (host.isNotBlank()) {
                candidates.add("$scheme://$host/")
                val parts = host.split(".")
                if (parts.size >= 2) {
                    val apex = parts.takeLast(2).joinToString(".")
                    candidates.add("$scheme://$apex/")
                    candidates.add("$scheme://www.$apex/")
                }
            }
            val merged = linkedMapOf<String, String>()
            for (cand in candidates) {
                val raw = cm.getCookie(cand).orEmpty()
                for (pair in raw.split(";")) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        val k = pair.substring(0, eq).trim()
                        val v = pair.substring(eq + 1).trim()
                        if (k.isNotBlank()) merged[k] = v
                    }
                }
            }
            merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }.getOrDefault("")
    }

    fun activeToolsSummaryForPrompt(): String {
        val statuses = auth.getPluginStatuses()
        val connected = statuses.filter { it.connected }
        val pending = statuses.filterNot { it.connected }
        return buildString {
            append("CONNECTED PLUGINS READY NOW (CALL THESE DIRECTLY IN JSON):\n")
            for (st in connected) {
                append("- ${st.definition.name} (${st.accountLabel}):\n")
                for (t in st.definition.tools) {
                    val tag = if (t.isWriteAction) " [WRITE->Human Approval]" else " [DIRECT]"
                    append("  * ${t.name}$tag: ${t.description} · call={\"action\":\"tool\",\"tool\":\"${t.name}\",\"args\":${t.argsSchema}}\n")
                }
            }
            append("\nEXECUTION RULE: Prefer invoking connected plugin tools DIRECTLY right away (or batch multiple via {\"action\":\"tools\",\"calls\":[{\"tool\":\"...\",\"args\":{...}}]}) before writing answers.\n")
            if (pending.isNotEmpty()) {
                append("\nPLUGINS WAITING FOR USER SIGN-IN IN SETTINGS:\n")
                append(pending.joinToString(", ") { "${it.definition.name} (${it.definition.id})" })
                append("\n(If a goal needs an unauthenticated plugin, use Browser as fallback if public, or state concisely which plugin needs sign-in.)")
            }
        }
    }

    fun isPluginTool(toolName: String): Boolean {
        return PluginRegistry.ALL.any { p -> p.tools.any { it.name == toolName } }
    }

    suspend fun execute(
        companyId: String,
        toolName: String,
        args: JSONObject,
        allowWriteExecution: Boolean = false,
    ): PluginToolResult = withContext(Dispatchers.IO) {
        val def = PluginRegistry.ALL.firstOrNull { p -> p.tools.any { it.name == toolName } }
        val toolMeta = def?.tools?.firstOrNull { it.name == toolName }

        // Enforce Human Approval Gate on all write tools unless already approved via Ship
        if (toolMeta?.isWriteAction == true && !allowWriteExecution) {
            return@withContext PluginToolResult(
                ok = true,
                output = "Action '$toolName' staged for founder approval (Ship / Not yet).",
                requiresApproval = true,
                approvalTitle = "Run ${def.name}: $toolName?",
                approvalSummary = args.toString().take(160),
            )
        }

        try {
            when (toolName) {
                "browser_search" -> execBrowserSearch(args.optString("query"))
                "browser_read_url", "kittl_read_workspace" -> {
                    val url = args.optString("url").ifBlank { "https://www.kittl.com/projects" }
                    execBrowserReadUrl(url)
                }
                "product_design_prototype" -> execProductDesignPrototype(companyId, args)
                "github_list_repos" -> execGitHubRepos()
                "github_read_file" -> execGitHubReadFile(args)
                "github_create_issue" -> execGitHubCreateIssue(args)
                "notion_search" -> execNotionSearch(args.optString("query"))
                "notion_read_page" -> execNotionReadPage(args.optString("page_id"))
                "notion_create_page" -> execNotionCreatePage(args)
                "notion_append_blocks" -> execNotionAppendBlocks(args)
                "slack_list_channels" -> execSlackListChannels()
                "slack_read_channel" -> execSlackReadChannel(args)
                "slack_post_message" -> execSlackPostMessage(args)
                "gmail_search" -> execGmailSearch(args)
                "gmail_read_message" -> execGmailRead(args.optString("message_id"))
                "gmail_send" -> execGmailSend(args)
                "gdrive_search" -> execDriveSearch(args.optString("query"))
                "gdrive_read_file" -> execDriveRead(args.optString("file_id"))
                "gdrive_upload" -> execDriveUpload(companyId, args)
                "figma_get_file" -> execFigmaGet("/files/${args.optString("file_key")}?depth=2")
                "figma_get_comments" -> execFigmaGet("/files/${args.optString("file_key")}/comments")
                "canva_list_designs" -> execCanvaDesigns(args.optString("query"))
                "scite_search" -> execSciteSearch(args.optString("query"))
                "tinyfish_extract" -> execTinyFish(args)
                "instagram_list_comments" -> execInstagramComments(args)
                "instagram_reply_comment" -> execInstagramReply(args)
                "instagram_publish_post" -> execInstagramPublish(args)
                "tiktok_list_videos" -> execTikTokVideos(args)
                "tiktok_publish_video" -> execTikTokPublish(args)
                "openhands_list_conversations" -> execOpenHandsList(args)
                "openhands_get_status" -> execOpenHandsStatus(args)
                "openhands_start_task" -> execOpenHandsStartTask(args)
                else -> PluginToolResult(false, "Unknown plugin tool: $toolName")
            }
        } catch (e: Exception) {
            PluginToolResult(false, "Plugin '$toolName' error: ${e.message}")
        }
    }

    private fun execBrowserSearch(query: String): PluginToolResult {
        if (query.isBlank()) return PluginToolResult(false, "Missing search query")
        val qEnc = enc(query)
        val aggregated = JSONArray()
        val errors = mutableListOf<String>()
        val ua = "CEO-Android/0.7 (https://github.com/Anisghd-lab/ceo-app)"

        // Source 1: Wikipedia OpenSearch JSON API (structured, fast, no bot challenge)
        val wikiBase = baseUrl("wikipedia", "https://en.wikipedia.org")
        runCatching {
            val req = Request.Builder()
                .url("$wikiBase/w/api.php?action=opensearch&search=$qEnc&limit=5&namespace=0&format=json")
                .header("User-Agent", ua)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    val titles = arr.optJSONArray(1) ?: JSONArray()
                    val descs = arr.optJSONArray(2) ?: JSONArray()
                    val urls = arr.optJSONArray(3) ?: JSONArray()
                    for (i in 0 until titles.length()) {
                        val u = urls.optString(i)
                        if (u.isNotBlank()) {
                            aggregated.put(
                                JSONObject()
                                    .put("source", "wikipedia")
                                    .put("title", titles.optString(i))
                                    .put("url", u)
                                    .put("snippet", descs.optString(i))
                            )
                        }
                    }
                } else {
                    errors.add("wikipedia HTTP ${resp.code}")
                }
            }
        }.onFailure { errors.add("wikipedia: ${it.message}") }

        // Source 2: DuckDuckGo Instant Answer JSON API
        val ddgBase = baseUrl("ddg_instant", "https://api.duckduckgo.com")
        runCatching {
            val req = Request.Builder()
                .url("$ddgBase/?q=$qEnc&format=json&no_html=1&skip_disambig=1")
                .header("User-Agent", ua)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val abs = obj.optString("AbstractText")
                    val absUrl = obj.optString("AbstractURL")
                    if (abs.isNotBlank() && absUrl.isNotBlank()) {
                        aggregated.put(
                            JSONObject()
                                .put("source", "duckduckgo_instant")
                                .put("title", obj.optString("Heading").ifBlank { query })
                                .put("url", absUrl)
                                .put("snippet", abs.take(320))
                        )
                    }
                    val related = obj.optJSONArray("RelatedTopics") ?: JSONArray()
                    for (i in 0 until minOf(related.length(), 4)) {
                        val rt = related.optJSONObject(i) ?: continue
                        val firstUrl = rt.optString("FirstURL")
                        val txt = rt.optString("Text")
                        if (firstUrl.isNotBlank() && txt.isNotBlank()) {
                            aggregated.put(
                                JSONObject()
                                    .put("source", "duckduckgo_related")
                                    .put("title", txt.substringBefore(" - ").take(90))
                                    .put("url", firstUrl)
                                    .put("snippet", txt.take(260))
                            )
                        }
                    }
                } else {
                    errors.add("ddg HTTP ${resp.code}")
                }
            }
        }.onFailure { errors.add("ddg: ${it.message}") }

        // Source 3: Hacker News Algolia Search JSON API (great for tech/startup/product research)
        val hnBase = baseUrl("hn_algolia", "https://hn.algolia.com")
        runCatching {
            val req = Request.Builder()
                .url("$hnBase/api/v1/search?query=$qEnc&hitsPerPage=4")
                .header("User-Agent", ua)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val hits = JSONObject(resp.body?.string().orEmpty()).optJSONArray("hits") ?: JSONArray()
                    for (i in 0 until hits.length()) {
                        val h = hits.optJSONObject(i) ?: continue
                        val title = h.optString("title")
                        val link = h.optString("url").ifBlank { "https://news.ycombinator.com/item?id=${h.optString("objectID")}" }
                        if (title.isNotBlank()) {
                            aggregated.put(
                                JSONObject()
                                    .put("source", "hackernews")
                                    .put("title", title)
                                    .put("url", link)
                                    .put("snippet", "points=${h.optInt("points", 0)}, comments=${h.optInt("num_comments", 0)}")
                            )
                        }
                    }
                } else {
                    errors.add("hn HTTP ${resp.code}")
                }
            }
        }.onFailure { errors.add("hn: ${it.message}") }

        return if (aggregated.length() > 0) {
            PluginToolResult(
                true,
                JSONObject()
                    .put("query", query)
                    .put("count", aggregated.length())
                    .put("results", aggregated)
                    .toString(),
            )
        } else {
            PluginToolResult(
                false,
                "Web search returned 0 results for '$query' (${errors.joinToString("; ").ifBlank { "no matches" }}).",
            )
        }
    }

    private suspend fun readViaWebViewSession(url: String): WebViewReadResult {
        sessionReaderForTest?.let { return it(url) }
        return webViewReader.readAuthenticatedPage(url)
    }

    private suspend fun execBrowserReadUrl(url: String): PluginToolResult {
        if (!url.startsWith("http")) return PluginToolResult(false, "Invalid URL: $url")
        val cookies = collectCookiesForUrl(url)
        val origin = runCatching {
            val u = java.net.URI(url)
            "${u.scheme ?: "https"}://${u.host}"
        }.getOrDefault(url)

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", "$origin/")
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)

        val (httpCode, okHttpOk, rawTitle, rawText) = runCatching {
            http.newCall(reqBuilder.get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(body)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()
                val text = stripHtml(body).take(5500)
                QuadResult(resp.code, resp.isSuccessful, title, text)
            }
        }.getOrElse {
            QuadResult(0, false, "", it.message.orEmpty())
        }

        // If OkHttp hits 404/403/401 OR an empty JS/SPA shell on an authenticated session,
        // execute cleanly inside the real WebView engine sharing CookieManager & DOM Storage!
        val needsWebViewRecovery = !okHttpOk || httpCode == 404 || httpCode == 403 || httpCode == 401 || rawText.length < 180
        if (needsWebViewRecovery) {
            val wvDirect = readViaWebViewSession(url)
            if (wvDirect.ok) {
                return PluginToolResult(
                    true,
                    JSONObject()
                        .put("status", 200)
                        .put("mode", "webview_session")
                        .put("url", wvDirect.finalUrl)
                        .put("title", wvDirect.title)
                        .put("authenticated_cookies_used", true)
                        .put("text", wvDirect.text)
                        .put("links", wvDirect.linksJson)
                        .toString(),
                )
            }
            // If deep path returned 404 (e.g. site moved /projects -> /dashboard), auto-recover via authenticated root origin!
            if (wvDirect.httpStatusHint == 404 || httpCode == 404) {
                if (origin != url && origin.startsWith("http")) {
                    val wvRoot = readViaWebViewSession(origin)
                    if (wvRoot.ok) {
                        return PluginToolResult(
                            true,
                            JSONObject()
                                .put("status", 200)
                                .put("mode", "webview_origin_recovery_from_404")
                                .put("requested_url", url)
                                .put("resolved_url", wvRoot.finalUrl)
                                .put("title", wvRoot.title)
                                .put("authenticated_cookies_used", true)
                                .put("text", wvRoot.text)
                                .put("links", wvRoot.linksJson)
                                .toString(),
                        )
                    }
                }
            }
        }

        return PluginToolResult(
            okHttpOk,
            JSONObject()
                .put("status", httpCode)
                .put("url", url)
                .put("title", rawTitle)
                .put("authenticated_cookies_used", cookies.isNotBlank())
                .put("text", rawText)
                .toString(),
        )
    }

    private data class QuadResult(val code: Int, val ok: Boolean, val title: String, val text: String)

    private fun execProductDesignPrototype(companyId: String, args: JSONObject): PluginToolResult {
        val rawFn = args.optString("filename").ifBlank { "ui_prototype.html" }
        val fn = rawFn.replace(Regex("[^a-zA-Z0-9._-]"), "_").let { if (it.endsWith(".html", true)) it else "$it.html" }
        val title = args.optString("title").ifBlank { "Product Prototype" }
        val html = args.optString("html").ifBlank {
            "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>$title</title><style>body{font-family:system-ui;margin:0;padding:24px;background:#f8fafc;color:#0f172a}.card{background:#fff;border:1px solid #e2e8f0;border-radius:16px;padding:20px;max-width:720px;margin:0 auto}</style></head><body><div class='card'><h1>$title</h1><p>${args.optString("summary")}</p></div></body></html>"
        }
        val file = sandbox.writeTextFile(companyId, "output/$fn", html)
        return PluginToolResult(
            true,
            JSONObject().put("ok", true).put("rel_path", "output/${file.name}").put("size_bytes", file.length()).toString(),
        )
    }

    private fun requireToken(pluginId: String, pluginName: String): String? {
        val tok = auth.getAccessToken(pluginId)
        return tok.takeIf { it.isNotBlank() }
    }

    private suspend fun fallbackToWebSessionIfAuthenticated(
        pluginId: String,
        dashboardUrl: String,
        missingMsg: String,
    ): PluginToolResult {
        val def = PluginRegistry.ALL.firstOrNull { it.id == pluginId }
        val webOk = def != null && auth.hasValidBrowserCookie(def)
        return if (webOk) {
            execBrowserReadUrl(dashboardUrl)
        } else {
            PluginToolResult(false, missingMsg)
        }
    }

    private suspend fun execGitHubRepos(): PluginToolResult {
        val tok = requireToken("github", "GitHub") ?: return fallbackToWebSessionIfAuthenticated(
            "github",
            "https://github.com/dashboard",
            "GitHub not connected. Connect GitHub in Settings -> Plugins.",
        )
        val req = Request.Builder().url("${baseUrl("github", "https://api.github.com")}/user/repos?sort=updated&per_page=15")
            .header("Authorization", "Bearer $tok").header("Accept", "application/vnd.github+json").get().build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return PluginToolResult(false, "GitHub HTTP ${resp.code}: ${raw.take(200)}")
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return PluginToolResult(true, raw.take(3000))
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                out.put(JSONObject().put("full_name", r.optString("full_name")).put("private", r.optBoolean("private")).put("url", r.optString("html_url")))
            }
            PluginToolResult(true, out.toString())
        }
    }

    private fun execGitHubReadFile(args: JSONObject): PluginToolResult {
        val tok = auth.getAccessToken("github")
        val owner = args.optString("owner")
        val repo = args.optString("repo")
        val path = args.optString("path").trimStart('/')
        val b = Request.Builder().url("${baseUrl("github", "https://api.github.com")}/repos/$owner/$repo/contents/$path")
            .header("Accept", "application/vnd.github.raw+json")
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
        return http.newCall(b.get().build()).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(6000))
        }
    }

    private fun execGitHubCreateIssue(args: JSONObject): PluginToolResult {
        val tok = requireToken("github", "GitHub") ?: return PluginToolResult(false, "GitHub token missing.")
        val owner = args.optString("owner")
        val repo = args.optString("repo")
        val payload = JSONObject().put("title", args.optString("title")).put("body", args.optString("body"))
        val req = Request.Builder().url("${baseUrl("github", "https://api.github.com")}/repos/$owner/$repo/issues")
            .header("Authorization", "Bearer $tok").header("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(1500))
        }
    }

    private suspend fun execNotionSearch(query: String): PluginToolResult {
        val tok = requireToken("notion", "Notion") ?: return fallbackToWebSessionIfAuthenticated(
            "notion",
            "https://www.notion.so",
            "Notion not connected. Connect Notion in Settings -> Plugins.",
        )
        val body = JSONObject().put("query", query).put("page_size", 10)
        val req = Request.Builder().url("${baseUrl("notion", "https://api.notion.com")}/v1/search")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
            .post(body.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5000))
        }
    }

    private fun execNotionReadPage(pageId: String): PluginToolResult {
        val tok = requireToken("notion", "Notion") ?: return PluginToolResult(false, "Notion not connected.")
        val req = Request.Builder().url("https://api.notion.com/v1/blocks/$pageId/children?page_size=50")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(6000))
        }
    }

    private fun execNotionCreatePage(args: JSONObject): PluginToolResult {
        val tok = requireToken("notion", "Notion") ?: return PluginToolResult(false, "Notion not connected.")
        val base = baseUrl("notion", "https://api.notion.com")
        val parentId = args.optString("parent_id").ifBlank { findDefaultNotionParentId(tok, base).orEmpty() }
        if (parentId.isBlank()) return PluginToolResult(false, "Missing parent_id and no accessible Notion workspace page found.")
        val title = args.optString("title").ifBlank { "CEO Brief" }
        val text = args.optString("markdown").ifBlank { args.optString("content") }
        val payload = JSONObject()
            .put("parent", JSONObject().put("page_id", parentId))
            .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", title))))))
            .put("children", markdownToNotionBlocks(text))
        val req = Request.Builder().url("$base/v1/pages")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
        }
    }

    private fun execNotionAppendBlocks(args: JSONObject): PluginToolResult {
        val tok = requireToken("notion", "Notion") ?: return PluginToolResult(false, "Notion not connected.")
        val pageId = args.optString("page_id").ifBlank { return PluginToolResult(false, "Missing page_id") }
        val text = args.optString("markdown").ifBlank { args.optString("content") }
        val base = baseUrl("notion", "https://api.notion.com")
        val payload = JSONObject().put("children", markdownToNotionBlocks(text))
        val req = Request.Builder().url("$base/v1/blocks/$pageId/children")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
            .patch(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
        }
    }

    private fun findDefaultNotionParentId(tok: String, base: String): String? {
        return runCatching {
            val body = JSONObject()
                .put("filter", JSONObject().put("value", "page").put("property", "object"))
                .put("page_size", 5)
            val req = Request.Builder().url("$base/v1/search")
                .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
                .post(body.toString().toRequestBody(jsonMedia)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return@runCatching null
                if (arr.length() > 0) arr.getJSONObject(0).optString("id").ifBlank { null } else null
            }
        }.getOrNull()
    }

    private fun markdownToNotionBlocks(markdown: String): JSONArray {
        val blocks = JSONArray()
        val clean = markdown.trim().ifBlank { "Updated by CEO" }
        val chunks = clean.chunked(1850).take(12)
        for (chunk in chunks) {
            blocks.put(
                JSONObject()
                    .put("object", "block")
                    .put("type", "paragraph")
                    .put(
                        "paragraph",
                        JSONObject().put(
                            "rich_text",
                            JSONArray().put(
                                JSONObject().put("type", "text").put("text", JSONObject().put("content", chunk))
                            )
                        )
                    )
            )
        }
        return blocks
    }

    /**
     * Ensures a dedicated Notion tracking & memory page exists for `companyName`
     * and appends a structured mission/progress update block cleanly.
     */
    fun ensureAndAppendCompanyNotionPage(
        companyName: String,
        objective: String,
        existingPageId: String,
        sectionTitle: String,
        entryMarkdown: String,
    ): Pair<String, String>? {
        val tok = requireToken("notion", "Notion") ?: return null
        val base = baseUrl("notion", "https://api.notion.com")

        if (existingPageId.isNotBlank()) {
            val payload = JSONObject().put("children", markdownToNotionBlocks("[$sectionTitle]\n$entryMarkdown"))
            val req = Request.Builder().url("$base/v1/blocks/$existingPageId/children")
                .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
                .patch(payload.toString().toRequestBody(jsonMedia)).build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val slugId = existingPageId.replace("-", "")
                    return existingPageId to "https://www.notion.so/$slugId"
                }
            }
        }

        val parentId = findDefaultNotionParentId(tok, base) ?: return null
        val initContent = buildString {
            append("Entreprise : $companyName\n")
            append("Objectif : $objective\n\n")
            append("[$sectionTitle]\n$entryMarkdown")
        }
        val payload = JSONObject()
            .put("parent", JSONObject().put("page_id", parentId))
            .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", "[CEO] $companyName — Suivi & Mémoire"))))))
            .put("children", markdownToNotionBlocks(initContent))
        val req = Request.Builder().url("$base/v1/pages")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return null
            val pid = obj.optString("id").ifBlank { return null }
            val url = obj.optString("url").ifBlank { "https://www.notion.so/${pid.replace("-", "")}" }
            pid to url
        }
    }

    private fun execSlackListChannels(): PluginToolResult {
        val tok = requireToken("slack", "Slack") ?: return PluginToolResult(false, "Slack not connected. Connect Slack in Settings -> Plugins.")
        val req = Request.Builder().url("https://slack.com/api/conversations.list?limit=25&types=public_channel,private_channel")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(4500))
        }
    }

    private fun execSlackReadChannel(args: JSONObject): PluginToolResult {
        val tok = requireToken("slack", "Slack") ?: return PluginToolResult(false, "Slack not connected.")
        val ch = args.optString("channel_id")
        val lim = args.optInt("limit", 15).coerceIn(1, 50)
        val req = Request.Builder().url("https://slack.com/api/conversations.history?channel=$ch&limit=$lim")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5000))
        }
    }

    private fun execSlackPostMessage(args: JSONObject): PluginToolResult {
        val tok = requireToken("slack", "Slack") ?: return PluginToolResult(false, "Slack not connected.")
        val payload = JSONObject().put("channel", args.optString("channel_id")).put("text", args.optString("text"))
        val req = Request.Builder().url("https://slack.com/api/chat.postMessage")
            .header("Authorization", "Bearer $tok").post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(1500))
        }
    }

    private fun execGmailSearch(args: JSONObject): PluginToolResult {
        val tok = requireToken("gmail", "Gmail") ?: return PluginToolResult(false, "Gmail not connected. Connect Gmail in Settings -> Plugins.")
        val q = enc(args.optString("query").ifBlank { "in:inbox" })
        val max = args.optInt("max", 10).coerceIn(1, 20)
        val req = Request.Builder().url("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=$q&maxResults=$max")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(4000))
        }
    }

    private fun execGmailRead(msgId: String): PluginToolResult {
        val tok = requireToken("gmail", "Gmail") ?: return PluginToolResult(false, "Gmail not connected.")
        val req = Request.Builder().url("https://gmail.googleapis.com/gmail/v1/users/me/messages/$msgId?format=full")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5000))
        }
    }

    private fun execGmailSend(args: JSONObject): PluginToolResult {
        val tok = requireToken("gmail", "Gmail") ?: return PluginToolResult(false, "Gmail not connected.")
        val rfc = "To: ${args.optString("to")}\r\nSubject: ${args.optString("subject")}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${args.optString("body")}"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rfc.toByteArray(Charsets.UTF_8))
        val payload = JSONObject().put("raw", encoded)
        val req = Request.Builder().url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
            .header("Authorization", "Bearer $tok").post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(1500))
        }
    }

    private fun execDriveSearch(query: String): PluginToolResult {
        val tok = requireToken("gdrive", "Google Drive") ?: return PluginToolResult(false, "Google Drive not connected. Connect Google Drive in Settings -> Plugins.")
        val q = enc("name contains '${query.replace("'", "\\'")}' and trashed = false")
        val req = Request.Builder().url("https://www.googleapis.com/drive/v3/files?q=$q&fields=files(id,name,mimeType,modifiedTime)&pageSize=15")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(4500))
        }
    }

    private fun execDriveRead(fileId: String): PluginToolResult {
        val tok = requireToken("gdrive", "Google Drive") ?: return PluginToolResult(false, "Google Drive not connected.")
        val req = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                PluginToolResult(true, resp.body?.string().orEmpty().take(5500))
            } else {
                val expReq = Request.Builder().url("https://www.googleapis.com/drive/v3/files/$fileId/export?mimeType=text/plain")
                    .header("Authorization", "Bearer $tok").get().build()
                http.newCall(expReq).execute().use { exp ->
                    PluginToolResult(exp.isSuccessful, exp.body?.string().orEmpty().take(5500))
                }
            }
        }
    }

    private fun execDriveUpload(companyId: String, args: JSONObject): PluginToolResult {
        val tok = requireToken("gdrive", "Google Drive") ?: return PluginToolResult(false, "Google Drive not connected.")
        val relPath = args.optString("rel_path")
        val f = sandbox.resolveFile(companyId, relPath)
        if (!f.exists()) return PluginToolResult(false, "Sandbox file not found: $relPath")
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=media")
            .header("Authorization", "Bearer $tok")
            .header("Content-Type", "application/octet-stream")
            .post(f.readBytes().toRequestBody())
            .build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(1500))
        }
    }

    private suspend fun execFigmaGet(path: String): PluginToolResult {
        val tok = requireToken("figma", "Figma") ?: return fallbackToWebSessionIfAuthenticated(
            "figma",
            "https://www.figma.com/files/recent",
            "Figma not connected. Connect Figma in Settings -> Plugins.",
        )
        val b = Request.Builder().url("https://api.figma.com/v1$path")
        if (tok.startsWith("figd_")) b.header("X-Figma-Token", tok) else b.header("Authorization", "Bearer $tok")
        return http.newCall(b.get().build()).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5500))
        }
    }

    private suspend fun execCanvaDesigns(query: String): PluginToolResult {
        val tok = requireToken("canva", "Canva") ?: return fallbackToWebSessionIfAuthenticated(
            "canva",
            "https://www.canva.com",
            "Canva not connected. Connect Canva in Settings -> Plugins.",
        )
        val q = if (query.isBlank()) "" else "?query=${enc(query)}"
        val req = Request.Builder().url("https://api.canva.com/rest/v1/designs$q")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(4500))
        }
    }

    private fun execSciteSearch(query: String): PluginToolResult {
        val tok = auth.getAccessToken("scite")
        val url = "https://api.scite.ai/search?term=${enc(query)}&limit=8"
        val b = Request.Builder().url(url).get()
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
        return http.newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) PluginToolResult(true, resp.body?.string().orEmpty().take(5500))
            else execBrowserSearch("$query site:scite.ai")
        }
    }

    private suspend fun execTinyFish(args: JSONObject): PluginToolResult {
        val tok = auth.getAccessToken("tinyfish")
        val targetUrl = args.optString("url")
        if (tok.isBlank()) {
            return execBrowserReadUrl(targetUrl)
        }
        val payload = JSONObject().put("url", targetUrl).put("goal", args.optString("goal"))
        val req = Request.Builder().url("https://agent.tinyfish.ai/v1/automation/run")
            .header("X-API-Key", tok).post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) PluginToolResult(true, resp.body?.string().orEmpty().take(5500))
            else execBrowserReadUrl(targetUrl)
        }
    }

    private fun execInstagramComments(args: JSONObject): PluginToolResult {
        val tok = requireToken("instagram", "Instagram")
            ?: return PluginToolResult(false, "Instagram not connected. Open Settings -> Plugins -> Instagram to sign in or add Graph token.")
        val mediaId = args.optString("media_id").ifBlank { return PluginToolResult(false, "Missing media_id") }
        val url = "${baseUrl("instagram", "https://graph.facebook.com/v20.0")}/$mediaId/comments?fields=id,text,username,timestamp&limit=25"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5000))
        }
    }

    private fun execInstagramReply(args: JSONObject): PluginToolResult {
        val tok = requireToken("instagram", "Instagram") ?: return PluginToolResult(false, "Instagram token missing.")
        val commentId = args.optString("comment_id").ifBlank { return PluginToolResult(false, "Missing comment_id") }
        val msg = args.optString("message").ifBlank { return PluginToolResult(false, "Missing message") }
        val payload = JSONObject().put("message", msg)
        val url = "${baseUrl("instagram", "https://graph.facebook.com/v20.0")}/$commentId/replies"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $tok")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
        }
    }

    private fun execInstagramPublish(args: JSONObject): PluginToolResult {
        val tok = requireToken("instagram", "Instagram") ?: return PluginToolResult(false, "Instagram token missing.")
        val igUser = args.optString("ig_user_id").ifBlank { return PluginToolResult(false, "Missing ig_user_id") }
        val base = baseUrl("instagram", "https://graph.facebook.com/v20.0")
        val createPayload = JSONObject()
            .put("image_url", args.optString("image_url"))
            .put("caption", args.optString("caption"))
        val createReq = Request.Builder().url("$base/$igUser/media")
            .header("Authorization", "Bearer $tok")
            .post(createPayload.toString().toRequestBody(jsonMedia)).build()
        val containerId = http.newCall(createReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return PluginToolResult(false, "Instagram container HTTP ${resp.code}: ${raw.take(200)}")
            JSONObject(raw).optString("id")
        }
        if (containerId.isBlank()) return PluginToolResult(false, "Instagram did not return a creation_id")
        val pubReq = Request.Builder().url("$base/$igUser/media_publish")
            .header("Authorization", "Bearer $tok")
            .post(JSONObject().put("creation_id", containerId).toString().toRequestBody(jsonMedia)).build()
        return http.newCall(pubReq).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
        }
    }

    private fun execTikTokVideos(args: JSONObject): PluginToolResult {
        val tok = requireToken("tiktok", "TikTok")
            ?: return PluginToolResult(false, "TikTok not connected. Open Settings -> Plugins -> TikTok to sign in or add OAuth token.")
        val maxCount = args.optInt("max_count", 10).coerceIn(1, 20)
        val base = baseUrl("tiktok", "https://open.tiktokapis.com/v2")
        val req = Request.Builder()
            .url("$base/video/list/?fields=id,title,video_description,view_count,like_count,comment_count")
            .header("Authorization", "Bearer $tok")
            .post(JSONObject().put("max_count", maxCount).toString().toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5000))
        }
    }

    private fun execTikTokPublish(args: JSONObject): PluginToolResult {
        val tok = requireToken("tiktok", "TikTok") ?: return PluginToolResult(false, "TikTok token missing.")
        val base = baseUrl("tiktok", "https://open.tiktokapis.com/v2")
        val payload = JSONObject()
            .put("post_info", JSONObject().put("title", args.optString("title")).put("privacy_level", "SELF_ONLY"))
            .put("source_info", JSONObject().put("source", "PULL_FROM_URL").put("video_url", args.optString("video_url")))
        val req = Request.Builder().url("$base/post/publish/video/init/")
            .header("Authorization", "Bearer $tok")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
        }
    }

    private fun execOpenHandsList(args: JSONObject): PluginToolResult {
        val tok = requireToken("openhands", "OpenHands Cloud")
            ?: return PluginToolResult(false, "OpenHands Cloud not connected. Open Settings -> Plugins -> OpenHands Cloud to add your API key from app.all-hands.dev/settings/api-keys.")
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val base = baseUrl("openhands", "https://app.all-hands.dev/api")
        val req = Request.Builder()
            .url("$base/conversations?limit=$limit")
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                PluginToolResult(false, "OpenHands list HTTP ${resp.code}: ${raw.take(400)}")
            } else {
                PluginToolResult(true, raw.take(6000))
            }
        }
    }

    private fun execOpenHandsStatus(args: JSONObject): PluginToolResult {
        val tok = requireToken("openhands", "OpenHands Cloud")
            ?: return PluginToolResult(false, "OpenHands Cloud API key missing.")
        val convId = args.optString("conversation_id").ifBlank { args.optString("id") }.trim()
        if (convId.isBlank()) return PluginToolResult(false, "Missing conversation_id for openhands_get_status")
        val base = baseUrl("openhands", "https://app.all-hands.dev/api")
        val req = Request.Builder()
            .url("$base/conversations/${enc(convId)}")
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                PluginToolResult(false, "OpenHands status HTTP ${resp.code}: ${raw.take(400)}")
            } else {
                PluginToolResult(true, raw.take(6000))
            }
        }
    }

    private fun execOpenHandsStartTask(args: JSONObject): PluginToolResult {
        val tok = requireToken("openhands", "OpenHands Cloud")
            ?: return PluginToolResult(false, "OpenHands Cloud API key missing. Add it in Settings -> Plugins -> OpenHands Cloud.")
        val msg = args.optString("initial_user_msg")
            .ifBlank { args.optString("prompt") }
            .ifBlank { args.optString("task") }
            .trim()
        if (msg.isBlank()) return PluginToolResult(false, "Missing initial_user_msg for openhands_start_task")
        val repo = args.optString("selected_repository").ifBlank { args.optString("repository") }.trim()
        val payload = JSONObject().put("initial_user_msg", msg)
        if (repo.isNotBlank()) payload.put("selected_repository", repo)
        val base = baseUrl("openhands", "https://app.all-hands.dev/api")
        val req = Request.Builder()
            .url("$base/conversations")
            .header("Authorization", "Bearer $tok")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        return http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                PluginToolResult(false, "OpenHands start task HTTP ${resp.code}: ${raw.take(400)}")
            } else {
                PluginToolResult(true, raw.take(4000))
            }
        }
    }

    private fun stripHtml(raw: String): String {
        return raw
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
