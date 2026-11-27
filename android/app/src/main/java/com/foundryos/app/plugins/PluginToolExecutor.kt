package com.foundryos.app.plugins

import android.content.Context
import android.net.Uri
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

    fun activeToolsSummaryForPrompt(): String {
        val statuses = auth.getPluginStatuses()
        val connected = statuses.filter { it.connected }
        val pending = statuses.filterNot { it.connected }
        return buildString {
            append("CONNECTED PLUGINS READY NOW:\n")
            for (st in connected) {
                append("- ${st.definition.name} (${st.accountLabel}):\n")
                for (t in st.definition.tools) {
                    val tag = if (t.isWriteAction) " [WRITE->Human Approval]" else ""
                    append("  * ${t.name}${tag}: ${t.description} · args=${t.argsSchema}\n")
                }
            }
            if (pending.isNotEmpty()) {
                append("\nPLUGINS WAITING FOR USER SIGN-IN IN SETTINGS:\n")
                append(pending.joinToString(", ") { "${it.definition.name} (${it.definition.id})" })
                append("\n(If a goal needs a plugin not yet connected, tell the user honestly which plugin to tap Connect on in Settings, or use Browser as fallback if public.)")
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
                else -> PluginToolResult(false, "Unknown plugin tool: $toolName")
            }
        } catch (e: Exception) {
            PluginToolResult(false, "Plugin '$toolName' error: ${e.message}")
        }
    }

    private fun execBrowserSearch(query: String): PluginToolResult {
        if (query.isBlank()) return PluginToolResult(false, "Missing search query")
        val url = "https://html.duckduckgo.com/html/?q=${Uri.encode(query)}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .get()
            .build()
        return http.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val linkRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val snipRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val links = linkRegex.findAll(html).take(8).toList()
            val snips = snipRegex.findAll(html).take(8).toList()
            val out = JSONArray()
            links.forEachIndexed { idx, m ->
                val rawHref = m.groupValues[1]
                val cleanTitle = stripHtml(m.groupValues[2])
                val snippet = snips.getOrNull(idx)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()
                out.put(JSONObject().put("title", cleanTitle).put("url", rawHref).put("snippet", snippet))
            }
            if (out.length() == 0) {
                PluginToolResult(true, stripHtml(html).take(3500))
            } else {
                PluginToolResult(true, out.toString())
            }
        }
    }

    private fun execBrowserReadUrl(url: String): PluginToolResult {
        if (!url.startsWith("http")) return PluginToolResult(false, "Invalid URL: $url")
        val cookies = runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
        if (cookies.isNotBlank()) reqBuilder.header("Cookie", cookies)

        return http.newCall(reqBuilder.get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.get(1)?.let { stripHtml(it) }.orEmpty()
            val text = stripHtml(body).take(5500)
            PluginToolResult(
                resp.isSuccessful,
                JSONObject()
                    .put("status", resp.code)
                    .put("url", url)
                    .put("title", title)
                    .put("authenticated_cookies_used", cookies.isNotBlank())
                    .put("text", text)
                    .toString(),
            )
        }
    }

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

    private fun execGitHubRepos(): PluginToolResult {
        val tok = requireToken("github", "GitHub") ?: return PluginToolResult(false, "GitHub not connected. Connect GitHub in Settings -> Plugins.")
        val req = Request.Builder().url("https://api.github.com/user/repos?sort=updated&per_page=15")
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
        val b = Request.Builder().url("https://api.github.com/repos/$owner/$repo/contents/$path")
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
        val req = Request.Builder().url("https://api.github.com/repos/$owner/$repo/issues")
            .header("Authorization", "Bearer $tok").header("Accept", "application/vnd.github+json")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(1500))
        }
    }

    private fun execNotionSearch(query: String): PluginToolResult {
        val tok = requireToken("notion", "Notion") ?: return PluginToolResult(false, "Notion not connected. Connect Notion in Settings -> Plugins.")
        val body = JSONObject().put("query", query).put("page_size", 10)
        val req = Request.Builder().url("https://api.notion.com/v1/search")
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
        val parentId = args.optString("parent_id")
        val title = args.optString("title").ifBlank { "CEO Brief" }
        val text = args.optString("markdown").ifBlank { args.optString("content") }
        val payload = JSONObject()
            .put("parent", JSONObject().put("page_id", parentId))
            .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", title))))))
            .put("children", JSONArray().put(JSONObject().put("object", "block").put("type", "paragraph").put("paragraph", JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", text.take(1900))))))))
        val req = Request.Builder().url("https://api.notion.com/v1/pages")
            .header("Authorization", "Bearer $tok").header("Notion-Version", "2022-06-28")
            .post(payload.toString().toRequestBody(jsonMedia)).build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(2000))
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
        val q = Uri.encode(args.optString("query").ifBlank { "in:inbox" })
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
        val q = Uri.encode("name contains '${query.replace("'", "\\'")}' and trashed = false")
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

    private fun execFigmaGet(path: String): PluginToolResult {
        val tok = requireToken("figma", "Figma") ?: return PluginToolResult(false, "Figma not connected. Connect Figma in Settings -> Plugins.")
        val b = Request.Builder().url("https://api.figma.com/v1$path")
        if (tok.startsWith("figd_")) b.header("X-Figma-Token", tok) else b.header("Authorization", "Bearer $tok")
        return http.newCall(b.get().build()).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(5500))
        }
    }

    private fun execCanvaDesigns(query: String): PluginToolResult {
        val tok = requireToken("canva", "Canva") ?: return PluginToolResult(false, "Canva not connected. Connect Canva in Settings -> Plugins.")
        val q = if (query.isBlank()) "" else "?query=${Uri.encode(query)}"
        val req = Request.Builder().url("https://api.canva.com/rest/v1/designs$q")
            .header("Authorization", "Bearer $tok").get().build()
        return http.newCall(req).execute().use { resp ->
            PluginToolResult(resp.isSuccessful, resp.body?.string().orEmpty().take(4500))
        }
    }

    private fun execSciteSearch(query: String): PluginToolResult {
        val tok = auth.getAccessToken("scite")
        val url = "https://api.scite.ai/search?term=${Uri.encode(query)}&limit=8"
        val b = Request.Builder().url(url).get()
        if (tok.isNotBlank()) b.header("Authorization", "Bearer $tok")
        return http.newCall(b.build()).execute().use { resp ->
            if (resp.isSuccessful) PluginToolResult(true, resp.body?.string().orEmpty().take(5500))
            else execBrowserSearch("$query site:scite.ai")
        }
    }

    private fun execTinyFish(args: JSONObject): PluginToolResult {
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
