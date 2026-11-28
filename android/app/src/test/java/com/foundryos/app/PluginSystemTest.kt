package com.foundryos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foundryos.app.engine.OnDeviceSandbox
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginRegistry
import com.foundryos.app.plugins.PluginToolExecutor
import com.foundryos.app.plugins.WebViewReadResult
import com.foundryos.app.service.AgentExecutionService
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PluginSystemTest {

    private lateinit var context: Context
    private lateinit var auth: PluginAuthManager
    private lateinit var sandbox: OnDeviceSandbox
    private lateinit var executor: PluginToolExecutor
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ceo_plugin_auth", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.resolve("sandboxes").deleteRecursively()
        auth = PluginAuthManager(context)
        sandbox = OnDeviceSandbox(context)
        executor = PluginToolExecutor(context, auth, sandbox)
        server = MockWebServer().apply { start() }
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun registry_containsFifteenPluginsAndBuiltInPluginsAreConnected() {
        assertEquals(15, PluginRegistry.ALL.size)
        val ids = PluginRegistry.ALL.map { it.id }.toSet()
        assertEquals(15, ids.size)
        assertTrue("browser" in ids)
        assertTrue("product_design" in ids)
        assertTrue("github" in ids)
        assertTrue("notion" in ids)
        assertTrue("instagram" in ids)
        assertTrue("tiktok" in ids)
        assertTrue("openhands" in ids)

        val summary = executor.activeToolsSummaryForPrompt()
        assertTrue(summary.contains("browser_search"))
        assertTrue(summary.contains("product_design_prototype"))
        assertTrue(summary.contains("PLUGINS WAITING FOR USER SIGN-IN"))
    }

    @Test
    fun browserSearch_aggregatesJsonApisAndReportsFailureWhenBlocked() = runTest {
        val base = server.url("").toString().trimEnd('/')
        executor.apiBaseOverrides["wikipedia"] = "$base/wiki"
        executor.apiBaseOverrides["ddg_instant"] = "$base/ddg"
        executor.apiBaseOverrides["hn_algolia"] = "$base/hn"

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """["android pdf",["PdfDocument"],["Native Android PDF API"],["https://en.wikipedia.org/wiki/PDF"]]"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"Heading":"Android SDK","AbstractText":"Mobile OS toolkit","AbstractURL":"https://developer.android.com","RelatedTopics":[]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"hits":[{"title":"Fast Android PDF Rendering","url":"https://news.ycombinator.com/item?id=1","points":42,"num_comments":5}]}"""
            )
        )

        val okRes = executor.execute("comp_test", "browser_search", JSONObject().put("query", "android pdf"))
        assertTrue("browser_search should succeed", okRes.ok)
        val json = JSONObject(okRes.output)
        assertEquals(3, json.getInt("count"))
        val sources = (0 until json.getJSONArray("results").length()).map {
            json.getJSONArray("results").getJSONObject(it).getString("source")
        }
        assertTrue(sources.containsAll(listOf("wikipedia", "duckduckgo_instant", "hackernews")))

        // Failure case when all upstream endpoints fail
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable")) }
        val failRes = executor.execute("comp_test", "browser_search", JSONObject().put("query", "android pdf"))
        assertFalse("browser_search must return ok=false when all endpoints fail", failRes.ok)
        assertTrue(failRes.output.contains("0 results"))
    }

    @Test
    fun writeToolsRequireApproval_andApprovedCallHitsRemoteApi() = runTest {
        auth.saveToken("github", "ghp_test_token_123", accountLabel = "octocat")
        executor.apiBaseOverrides["github"] = server.url("").toString().trimEnd('/')

        // Stage without approval -> must pause & require approval without hitting HTTP
        val staged = executor.execute(
            companyId = "comp_gh",
            toolName = "github_create_issue",
            args = JSONObject().put("owner", "octo").put("repo", "app").put("title", "Bug").put("body", "Details"),
            allowWriteExecution = false,
        )
        assertTrue(staged.ok)
        assertTrue("Write action must require approval", staged.requiresApproval)
        assertEquals(0, server.requestCount)

        // Approved execution -> sends real POST with Bearer header
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":99,"number":12}"""))
        val executed = executor.execute(
            companyId = "comp_gh",
            toolName = "github_create_issue",
            args = JSONObject().put("owner", "octo").put("repo", "app").put("title", "Bug").put("body", "Details"),
            allowWriteExecution = true,
        )
        assertTrue(executed.ok)
        assertFalse(executed.requiresApproval)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/repos/octo/app/issues", req.path)
        assertEquals("Bearer ghp_test_token_123", req.getHeader("Authorization"))
    }

    @Test
    fun productDesignPrototype_writesVerifiedHtmlDeliverableInSandbox() = runTest {
        val res = executor.execute(
            companyId = "comp_design",
            toolName = "product_design_prototype",
            args = JSONObject()
                .put("filename", "landing")
                .put("title", "FocusFlow MVP")
                .put("summary", "Autonomous launch page built on Android."),
        )
        assertTrue(res.ok)
        val out = JSONObject(res.output)
        assertEquals("output/landing.html", out.getString("rel_path"))
        val qa = sandbox.verifyArtifactProgrammatically("comp_design", "output/landing.html")
        assertTrue("Generated HTML prototype should pass programmatic QA: ${qa.report}", qa.passed)
    }

    @Test
    fun instagramAndTikTok_gateWritesBehindApprovalAndCallApiWhenApproved() = runTest {
        auth.saveToken("instagram", "ig_tok_test", accountLabel = "@formation_pro")
        executor.apiBaseOverrides["instagram"] = server.url("").toString().trimEnd('/')

        val staged = executor.execute(
            companyId = "comp_social",
            toolName = "instagram_reply_comment",
            args = JSONObject().put("comment_id", "cmt_77").put("message", "Lien d'inscription envoyé en DM!"),
            allowWriteExecution = false,
        )
        assertTrue(staged.ok)
        assertTrue(staged.requiresApproval)
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"reply_901"}"""))
        val exec = executor.execute(
            companyId = "comp_social",
            toolName = "instagram_reply_comment",
            args = JSONObject().put("comment_id", "cmt_77").put("message", "Lien d'inscription envoyé en DM!"),
            allowWriteExecution = true,
        )
        assertTrue(exec.ok)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/cmt_77/replies", req.path)
        assertEquals("Bearer ig_tok_test", req.getHeader("Authorization"))
    }

    @Test
    fun browserReadUrl_recoversFrom404ViaWebViewSessionAndOriginDashboard() = runTest {
        val base = server.url("").toString().trimEnd('/')
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html><title>404 Not Found</title></html>"))

        val visited = mutableListOf<String>()
        executor.sessionReaderForTest = { requested ->
            visited.add(requested)
            if (requested.endsWith("/projects")) {
                WebViewReadResult(
                    ok = false,
                    finalUrl = requested,
                    title = "404 Not Found",
                    text = "",
                    httpStatusHint = 404,
                )
            } else {
                WebViewReadResult(
                    ok = true,
                    finalUrl = "$base/dashboard",
                    title = "Kittl Dashboard",
                    text = "Welcome Creator — My Projects & Templates loaded with browser session",
                    linksJson = """[{"text":"Templates","href":"$base/templates"}]""",
                    httpStatusHint = 200,
                )
            }
        }

        val res = executor.execute(
            companyId = "comp_browser_404",
            toolName = "browser_read_url",
            args = JSONObject().put("url", "$base/projects"),
        )

        assertTrue("404 deep path must recover cleanly via WebView origin dashboard: ${res.output}", res.ok)
        val out = JSONObject(res.output)
        assertEquals("webview_origin_recovery_from_404", out.getString("mode"))
        assertTrue(out.getBoolean("authenticated_cookies_used"))
        assertTrue(out.getString("text").contains("My Projects"))
        assertTrue("Expected WebView recovery visit to $base/projects and origin $base", visited.size >= 2)
    }

    @Test
    fun openHandsCloud_gatesTaskStartBehindApprovalAndCallsApiWhenApproved() = runTest {
        auth.saveToken("openhands", "oh_pat_live_test", accountLabel = "OpenHands Cloud")
        executor.apiBaseOverrides["openhands"] = server.url("").toString().trimEnd('/')

        // 1) Starting a cloud coding task requires Human Approval first
        val staged = executor.execute(
            companyId = "comp_oh",
            toolName = "openhands_start_task",
            args = JSONObject()
                .put("initial_user_msg", "Fix pagination bug and add unit tests")
                .put("selected_repository", "Anisghd-lab/ceo-app"),
            allowWriteExecution = false,
        )
        assertTrue(staged.ok)
        assertTrue("openhands_start_task must require approval before dispatching cloud compute", staged.requiresApproval)
        assertEquals(0, server.requestCount)

        // 2) Approved execution hits POST /conversations with Bearer auth and JSON payload
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"conversation_id":"conv_oh_42","status":"RUNNING"}""")
        )
        val started = executor.execute(
            companyId = "comp_oh",
            toolName = "openhands_start_task",
            args = JSONObject()
                .put("initial_user_msg", "Fix pagination bug and add unit tests")
                .put("selected_repository", "Anisghd-lab/ceo-app"),
            allowWriteExecution = true,
        )
        assertTrue("Approved OpenHands task must succeed: ${started.output}", started.ok)
        assertFalse(started.requiresApproval)
        assertTrue(started.output.contains("conv_oh_42"))
        val postReq = server.takeRequest()
        assertEquals("POST", postReq.method)
        assertEquals("/conversations", postReq.path)
        assertEquals("Bearer oh_pat_live_test", postReq.getHeader("Authorization"))
        val sentJson = JSONObject(postReq.body.readUtf8())
        assertEquals("Fix pagination bug and add unit tests", sentJson.getString("initial_user_msg"))
        assertEquals("Anisghd-lab/ceo-app", sentJson.getString("selected_repository"))

        // 3) Read status works directly without approval gate
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"conversation_id":"conv_oh_42","status":"FINISHED","pr_url":"https://github.com/Anisghd-lab/ceo-app/pull/7"}""")
        )
        val statusRes = executor.execute(
            companyId = "comp_oh",
            toolName = "openhands_get_status",
            args = JSONObject().put("conversation_id", "conv_oh_42"),
        )
        assertTrue(statusRes.ok)
        assertFalse(statusRes.requiresApproval)
        assertTrue(statusRes.output.contains("FINISHED"))
        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertEquals("/conversations/conv_oh_42", getReq.path)
        assertEquals("Bearer oh_pat_live_test", getReq.getHeader("Authorization"))

        // 4) List conversations works directly
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"conversation_id":"conv_oh_42"}]}"""))
        val listRes = executor.execute(
            companyId = "comp_oh",
            toolName = "openhands_list_conversations",
            args = JSONObject().put("limit", 5),
        )
        assertTrue(listRes.ok)
        val listReq = server.takeRequest()
        assertEquals("GET", listReq.method)
        assertEquals("/conversations?limit=5", listReq.path)
    }

    @Test
    fun agentExecutionService_ensureChannelAndEnqueueWorksHeadless() {
        AgentExecutionService.ensureChannel(context)
        AgentExecutionService.enqueueTask(context, "Background verification task") {
            // Headless Robolectric dispatch should never throw SecurityException or crash
        }
        assertTrue(true)
    }
}
