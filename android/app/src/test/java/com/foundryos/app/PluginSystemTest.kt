package com.foundryos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foundryos.app.engine.OnDeviceSandbox
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginRegistry
import com.foundryos.app.plugins.PluginToolExecutor
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
    fun registry_containsFourteenPluginsAndBuiltInPluginsAreConnected() {
        assertEquals(14, PluginRegistry.ALL.size)
        val ids = PluginRegistry.ALL.map { it.id }.toSet()
        assertEquals(14, ids.size)
        assertTrue("browser" in ids)
        assertTrue("product_design" in ids)
        assertTrue("github" in ids)
        assertTrue("notion" in ids)
        assertTrue("instagram" in ids)
        assertTrue("tiktok" in ids)

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
}
