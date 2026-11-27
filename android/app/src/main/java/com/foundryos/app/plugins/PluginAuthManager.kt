package com.foundryos.app.plugins

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import com.foundryos.app.data.PluginAuthType
import com.foundryos.app.data.PluginDefinition
import com.foundryos.app.data.PluginStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

data class PluginLoginLaunch(
    val ok: Boolean,
    val urlToOpen: String = "",
    val message: String = "",
    val isOAuthLoopback: Boolean = false,
)

/**
 * Handles ChatGPT-style plugin authentication directly on Android (Zero VPS):
 * - Ephemeral loopback HTTP server (127.0.0.1:<port>) + OAuth 2.0 PKCE & MCP Dynamic Client Registration
 * - In-App Browser WebView session cookie detection (Kittl, Notion, GitHub, Figma, etc.)
 * - Token / API key / optional OAuth Client ID persistence
 */
class PluginAuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("ceo_plugins_auth", Context.MODE_PRIVATE)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCallbackServer: ServerSocket? = null

    fun getAccessToken(pluginId: String): String =
        prefs.getString("tok_$pluginId", "").orEmpty().trim()

    fun getAccountLabel(pluginId: String): String =
        prefs.getString("lbl_$pluginId", "").orEmpty().trim()

    fun getCustomClientId(pluginId: String): String =
        prefs.getString("cid_$pluginId", "").orEmpty().trim()

    fun setCustomClientId(pluginId: String, clientId: String) {
        prefs.edit().putString("cid_$pluginId", clientId.trim()).apply()
    }

    fun getMcpEndpointTokenUrl(pluginId: String): String =
        prefs.getString("mcp_tok_url_$pluginId", "").orEmpty()

    fun saveToken(pluginId: String, token: String, refreshToken: String = "", accountLabel: String = "") {
        prefs.edit()
            .putString("tok_$pluginId", token.trim())
            .apply {
                if (refreshToken.isNotBlank()) putString("ref_$pluginId", refreshToken.trim())
                if (accountLabel.isNotBlank()) putString("lbl_$pluginId", accountLabel.trim())
            }
            .apply()
    }

    fun markWebSessionConnected(pluginId: String, label: String = "Signed in via Browser") {
        prefs.edit()
            .putBoolean("web_$pluginId", true)
            .putString("lbl_$pluginId", label)
            .apply()
    }

    fun hasValidBrowserCookie(plugin: PluginDefinition): Boolean {
        val domain = plugin.cookieDomain.ifBlank { plugin.loginUrl }
        if (domain.isBlank()) return false
        return runCatching {
            val cookie = CookieManager.getInstance().getCookie(domain).orEmpty()
            cookie.length > 24 && cookie.contains("=")
        }.getOrDefault(false)
    }

    fun disconnect(pluginId: String) {
        prefs.edit()
            .remove("tok_$pluginId")
            .remove("ref_$pluginId")
            .remove("lbl_$pluginId")
            .remove("web_$pluginId")
            .apply()
    }

    fun getPluginStatuses(): List<PluginStatus> {
        return PluginRegistry.ALL.map { def ->
            val token = getAccessToken(def.id)
            val webFlag = prefs.getBoolean("web_${def.id}", false)
            val cookieOk = hasValidBrowserCookie(def)
            val connected = when (def.authType) {
                PluginAuthType.NONE_READY -> true
                PluginAuthType.WEB_SESSION -> webFlag || cookieOk
                else -> token.isNotBlank() || cookieOk
            }
            val label = when {
                def.authType == PluginAuthType.NONE_READY -> "Built-in · Ready"
                token.isNotBlank() -> getAccountLabel(def.id).ifBlank { "Token Active ✓" }
                webFlag || cookieOk -> getAccountLabel(def.id).ifBlank { "Browser Session Active" }
                else -> "Waiting for connection"
            }
            val detail = when {
                def.authType == PluginAuthType.NONE_READY -> "Available to all bots automatically"
                token.isNotBlank() -> "API / OAuth connected"
                cookieOk -> "Authenticated via CEO Browser cookies"
                def.authType == PluginAuthType.MCP_OAUTH -> "Connect in browser (OAuth / Token)"
                def.authType == PluginAuthType.OAUTH_PKCE -> "OAuth PKCE / Token ready"
                def.authType == PluginAuthType.DEVICE_FLOW -> "GitHub Browser / PAT ready"
                def.authType == PluginAuthType.API_KEY -> "Sign in via Browser or paste key"
                else -> "Sign in inside CEO Browser"
            }
            PluginStatus(
                definition = def,
                connected = connected,
                accountLabel = label,
                statusDetail = detail,
                hasCustomClientId = getCustomClientId(def.id).isNotBlank(),
            )
        }
    }

    fun syncBrowserCookiesForUrl(url: String): Boolean {
        var updated = false
        for (def in PluginRegistry.ALL) {
            val dom = def.cookieDomain.ifBlank { def.loginUrl }
            if (dom.isBlank()) continue
            val host = runCatching { Uri.parse(dom).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            if (host.isNotBlank() && url.contains(host, ignoreCase = true) && !url.contains("login", true)) {
                if (hasValidBrowserCookie(def)) {
                    if (!prefs.getBoolean("web_${def.id}", false)) {
                        markWebSessionConnected(def.id, "Connected ($host)")
                        updated = true
                    }
                }
            }
        }
        return updated
    }

    /**
     * Prepares the exact URL to open in CEO's In-App Browser for connecting a plugin.
     * Supports MCP Dynamic Client Registration + PKCE, Standard OAuth 2.0 PKCE on 127.0.0.1 loopback,
     * and direct portal login for cookie/token capture.
     */
    suspend fun beginConnectInBrowser(
        pluginId: String,
        onOAuthCompleted: suspend (Boolean, String) -> Unit = { _, _ -> },
    ): PluginLoginLaunch = withContext(Dispatchers.IO) {
        val plugin = PluginRegistry.byId(pluginId)
            ?: return@withContext PluginLoginLaunch(false, message = "Unknown plugin")

        if (plugin.authType == PluginAuthType.NONE_READY) {
            return@withContext PluginLoginLaunch(true, plugin.loginUrl.ifBlank { "https://www.google.com" }, "${plugin.name} is already active")
        }

        // Try MCP Dynamic Client Registration + PKCE first for Notion / Figma MCP
        if (plugin.authType == PluginAuthType.MCP_OAUTH && plugin.mcpUrl.isNotBlank()) {
            val mcpLaunch = tryStartMcpOAuthPkce(plugin, onOAuthCompleted)
            if (mcpLaunch != null) return@withContext mcpLaunch
        }

        // Standard OAuth 2.0 PKCE if client_id is configured
        val clientId = getCustomClientId(plugin.id).ifBlank { plugin.defaultClientId }
        if (clientId.isNotBlank() && plugin.authorizeUrl.isNotBlank()) {
            return@withContext startStandardOAuthPkce(plugin, clientId, plugin.authorizeUrl, plugin.tokenUrl, onOAuthCompleted)
        }

        // Otherwise open the service login / token portal cleanly inside CEO In-App Browser!
        // As soon as user signs in, WebView cookies are captured automatically OR user can paste a key/PAT.
        val portal = when {
            plugin.authType == PluginAuthType.WEB_SESSION -> plugin.loginUrl
            plugin.loginUrl.isNotBlank() -> plugin.loginUrl
            plugin.keyPortalUrl.isNotBlank() -> plugin.keyPortalUrl
            else -> "https://www.google.com"
        }
        PluginLoginLaunch(
            ok = true,
            urlToOpen = portal,
            message = "Sign in to ${plugin.name} in the browser — CEO keeps your session ready for bots.",
            isOAuthLoopback = false,
        )
    }

    private fun tryStartMcpOAuthPkce(
        plugin: PluginDefinition,
        onOAuthCompleted: suspend (Boolean, String) -> Unit,
    ): PluginLoginLaunch? {
        return runCatching {
            val uri = Uri.parse(plugin.mcpUrl)
            val origin = "${uri.scheme}://${uri.host}"
            val wellKnownUrl = "$origin/.well-known/oauth-authorization-server"
            val metaReq = Request.Builder().url(wellKnownUrl).get().build()
            val metaObj = http.newCall(metaReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
            }
            val authEndpoint = metaObj.optString("authorization_endpoint")
            val tokenEndpoint = metaObj.optString("token_endpoint")
            val regEndpoint = metaObj.optString("registration_endpoint")
            if (authEndpoint.isBlank() || tokenEndpoint.isBlank() || regEndpoint.isBlank()) return null

            val port = startLoopbackServerOnce(plugin, tokenEndpoint, "", onOAuthCompleted) ?: return null
            val redirectUri = "http://127.0.0.1:$port/callback"

            val regPayload = JSONObject()
                .put("client_name", "CEO Android App")
                .put("redirect_uris", JSONArray().put(redirectUri))
                .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none")
            val regReq = Request.Builder()
                .url(regEndpoint)
                .post(regPayload.toString().toRequestBody(jsonMedia))
                .build()
            val dynClientId = http.newCall(regReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    runCatching { activeCallbackServer?.close() }
                    return null
                }
                JSONObject(resp.body?.string().orEmpty()).optString("client_id")
            }
            if (dynClientId.isBlank()) {
                runCatching { activeCallbackServer?.close() }
                return null
            }

            launchLoopbackCodeExchange(plugin, dynClientId, authEndpoint, tokenEndpoint, port, redirectUri, onOAuthCompleted)
        }.getOrNull()
    }

    private fun startStandardOAuthPkce(
        plugin: PluginDefinition,
        clientId: String,
        authEndpoint: String,
        tokenEndpoint: String,
        onOAuthCompleted: suspend (Boolean, String) -> Unit,
    ): PluginLoginLaunch {
        val port = startLoopbackServerOnce(plugin, tokenEndpoint, clientId, onOAuthCompleted)
            ?: return PluginLoginLaunch(false, message = "Could not bind local OAuth callback port")
        val redirectUri = "http://127.0.0.1:$port/callback"
        return launchLoopbackCodeExchange(plugin, clientId, authEndpoint, tokenEndpoint, port, redirectUri, onOAuthCompleted)
    }

    private fun startLoopbackServerOnce(
        plugin: PluginDefinition,
        tokenEndpoint: String,
        clientId: String,
        onOAuthCompleted: suspend (Boolean, String) -> Unit,
    ): Int? {
        runCatching { activeCallbackServer?.close() }
        return runCatching {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            activeCallbackServer = server
            val port = server.localPort
            // Server accept happens in Thread via launchLoopbackCodeExchange
            port
        }.getOrNull()
    }

    private fun launchLoopbackCodeExchange(
        plugin: PluginDefinition,
        clientId: String,
        authEndpoint: String,
        tokenEndpoint: String,
        port: Int,
        redirectUri: String,
        onOAuthCompleted: suspend (Boolean, String) -> Unit,
    ): PluginLoginLaunch {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(12).also { SecureRandom().nextBytes(it) })

        val uriBuilder = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
        if (plugin.scopes.isNotBlank()) {
            uriBuilder.appendQueryParameter("scope", plugin.scopes)
        }
        val authUrl = uriBuilder.build().toString()

        Thread {
            val server = activeCallbackServer ?: return@Thread
            runCatching {
                server.soTimeout = 180_000
                val socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val reqLine = reader.readLine().orEmpty()
                val pathPart = reqLine.split(" ").getOrNull(1).orEmpty()
                val cbUri = Uri.parse("http://127.0.0.1:$port$pathPart")
                val code = cbUri.getQueryParameter("code").orEmpty()
                val err = cbUri.getQueryParameter("error").orEmpty()

                val htmlResp = if (code.isNotBlank()) {
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html><body style='font-family:sans-serif;padding:24px'><h2>Connected ${plugin.name} to CEO</h2><p>You can return to the CEO workspace.</p></body></html>"
                } else {
                    "HTTP/1.1 400 Bad Request\r\nContent-Type: text/html\r\n\r\n<html><body><h2>Authorization failed: $err</h2></body></html>"
                }
                socket.getOutputStream().write(htmlResp.toByteArray(Charsets.UTF_8))
                socket.close()
                server.close()

                if (code.isNotBlank() && tokenEndpoint.isNotBlank()) {
                    val form = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("client_id", clientId)
                        .add("code", code)
                        .add("redirect_uri", redirectUri)
                        .add("code_verifier", verifier)
                        .build()
                    val tokReq = Request.Builder().url(tokenEndpoint).post(form).build()
                    http.newCall(tokReq).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        val obj = runCatching { JSONObject(body) }.getOrNull()
                        val access = obj?.optString("access_token").orEmpty()
                        val refresh = obj?.optString("refresh_token").orEmpty()
                        if (resp.isSuccessful && access.isNotBlank()) {
                            saveToken(plugin.id, access, refresh, "${plugin.name} OAuth ✓")
                            prefs.edit().putString("mcp_tok_url_${plugin.id}", tokenEndpoint).apply()
                            kotlinx.coroutines.runBlocking { onOAuthCompleted(true, "${plugin.name} connected!") }
                        } else {
                            kotlinx.coroutines.runBlocking {
                                onOAuthCompleted(false, "Token exchange error: ${obj?.optString("error_description").orEmpty().ifBlank { body.take(120) }}")
                            }
                        }
                    }
                }
            }
        }.start()

        return PluginLoginLaunch(
            ok = true,
            urlToOpen = authUrl,
            message = "Connecting ${plugin.name} via OAuth PKCE...",
            isOAuthLoopback = true,
        )
    }
}
