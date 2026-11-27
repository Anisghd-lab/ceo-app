package com.foundryos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foundryos.app.engine.AutonomousEngine
import com.foundryos.app.engine.LocalDatabase
import com.foundryos.app.engine.OnDeviceLLMClient
import com.foundryos.app.engine.OnDeviceSandbox
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginToolExecutor
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutonomousBotsEndToEndTest {

    private lateinit var context: Context
    private lateinit var db: LocalDatabase
    private lateinit var sandbox: OnDeviceSandbox
    private lateinit var llm: OnDeviceLLMClient
    private lateinit var auth: PluginAuthManager
    private lateinit var pluginExec: PluginToolExecutor
    private lateinit var engine: AutonomousEngine
    private lateinit var aiServer: MockWebServer
    private lateinit var pluginServer: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf("ceo_llm_keys", "ceo_plugin_auth", "ceo_engine_prefs", "ceo_app_state").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.filesDir.resolve("sandboxes").deleteRecursively()
        context.deleteDatabase("foundry_os.db")

        db = LocalDatabase(context)
        sandbox = OnDeviceSandbox(context)
        llm = OnDeviceLLMClient(context)
        auth = PluginAuthManager(context)
        pluginExec = PluginToolExecutor(context, auth, sandbox)

        aiServer = MockWebServer().apply { start() }
        pluginServer = MockWebServer().apply { start() }

        // Wire local custom OpenAI-compatible mock LLM endpoint
        val slug = llm.addCustomProvider("mock-ai", aiServer.url("/v1").toString(), "openai")
        llm.saveProviderKey(slug, "sk-test-local")
        llm.saveProviderModel(slug, "mock-reasoner-1")

        val pBase = pluginServer.url("").toString().trimEnd('/')
        pluginExec.apiBaseOverrides["wikipedia"] = "$pBase/wiki"
        pluginExec.apiBaseOverrides["ddg_instant"] = "$pBase/ddg"
        pluginExec.apiBaseOverrides["hn_algolia"] = "$pBase/hn"
        pluginExec.apiBaseOverrides["github"] = "$pBase/github"
        auth.saveManualToken("github", "ghp_mock_bot_token", "founder-gh")

        engine = AutonomousEngine(
            context = context,
            db = db,
            sandbox = sandbox,
            llm = llm,
            agy = null,
            pluginAuth = auth,
            pluginExecutor = pluginExec,
        )
    }

    @After
    fun teardown() {
        aiServer.shutdown()
        pluginServer.shutdown()
        db.close()
    }

    private fun openAiWrap(content: String, tokens: Int = 120): MockResponse {
        val body = JSONObject().apply {
            put("choices", org.json.JSONArray().put(JSONObject().apply {
                put("message", JSONObject().put("content", content))
            }))
            put("usage", JSONObject().put("total_tokens", tokens))
        }
        return MockResponse().setResponseCode(200).setBody(body.toString())
    }

    @Test
    fun extractJsonObject_handlesProseAndCodeFencesSafely() {
        val raw = """
            Voici mon raisonnement {note interne} avant de lancer l'outil :
            {"action":"tool","tool":"browser_search","args":{"query":"Android {SDK}"}}
            Merci.
        """.trimIndent()
        val parsed = llm.extractJsonObject(raw)
        assertNotNull(parsed)
        assertEquals("tool", parsed?.optString("action"))
        assertEquals("browser_search", parsed?.optString("tool"))
        assertEquals("Android {SDK}", parsed?.optJSONObject("args")?.optString("query"))
    }

    @Test
    fun proposeCompany_preservesPluginToolsOnSpecialistBots() = runTest {
        val proposalJson = """
            {
              "name": "NovaSEO",
              "mission_statement": "Automate SEO audits using real web search and GitHub tracking.",
              "strategy_summary": "Strategist researches competitors, Builder produces audit PDFs, Publisher builds HTML report, QA validates.",
              "recommended_connectors": ["GitHub", "Notion"],
              "bots": [
                {"name": "Strategist", "role": "Market & SEO Researcher", "tagline": "Researches market via plugins", "system_prompt": "Use browser_search", "allowed_tools": ["browser_search", "browser_read_url", "company_memory"], "is_qa": false},
                {"name": "Builder", "role": "Report Engineer", "tagline": "Produces verified PDFs", "system_prompt": "Use github_list_repos & create_pdf", "allowed_tools": ["github_list_repos", "create_pdf", "sandbox_files"], "is_qa": false},
                {"name": "Publisher", "role": "Design & Launch Specialist", "tagline": "Crafts HTML showcase", "system_prompt": "Use product_design_prototype", "allowed_tools": ["product_design_prototype", "artifact_publish"], "is_qa": false},
                {"name": "Memory", "role": "Memory Keeper", "tagline": "Syncs lessons", "system_prompt": "Store learnings", "allowed_tools": ["company_memory"], "is_qa": false},
                {"name": "Quality Auditor", "role": "Independent QA", "tagline": "Audits deliverables", "system_prompt": "Verify quality", "allowed_tools": ["qa_verify", "sandbox_files"], "is_qa": true}
              ]
            }
        """.trimIndent()
        aiServer.enqueue(openAiWrap(proposalJson))

        val proposal = engine.proposeCompany("Launch an SEO audit agency")
        assertEquals("NovaSEO", proposal.name)
        val strategist = proposal.bots.first { it.name == "Strategist" }
        assertTrue("Plugin tool browser_search must be preserved on Strategist", strategist.allowedTools.contains("browser_search"))
        assertTrue("Strategist prompt must include company objective & role", strategist.systemPrompt.contains("NovaSEO") && strategist.systemPrompt.contains("Market & SEO Researcher"))
        val builder = proposal.bots.first { it.name == "Builder" }
        assertTrue("Plugin tool github_list_repos must be preserved on Builder", builder.allowedTools.contains("github_list_repos"))
        assertTrue("Builder prompt must include assigned role & objective", builder.systemPrompt.contains("Report Engineer") && builder.systemPrompt.contains("NovaSEO"))
        val publisher = proposal.bots.first { it.name == "Publisher" }
        assertTrue("Plugin tool product_design_prototype must be preserved on Publisher", publisher.allowedTools.contains("product_design_prototype"))
        assertTrue(proposal.bots.any { it.isQa })
        assertTrue(proposal.bots.any { it.isCeo })
    }

    @Test
    fun fullAutonomousMission_executesAllBotsWithPluginsReportsQaAndApprovalGate() = runTest {
        // Seed company + 5 specialist bots
        aiServer.enqueue(
            openAiWrap(
                """{"name":"NovaSEO","mission_statement":"Audit SEO & build interactive reports.","strategy_summary":"Team runs autonomously with plugins.","recommended_connectors":["GitHub"],"bots":[
                    {"name":"Strategist","role":"SEO Researcher","tagline":"Web research","system_prompt":"Research market","allowed_tools":["browser_search","web_fetch"],"is_qa":false},
                    {"name":"Builder","role":"PDF Architect","tagline":"Builds PDF","system_prompt":"Read repos & write PDF","allowed_tools":["github_list_repos","create_pdf"],"is_qa":false},
                    {"name":"Publisher","role":"HTML Designer","tagline":"Prototypes","system_prompt":"Build HTML prototype","allowed_tools":["product_design_prototype","artifact_publish"],"is_qa":false},
                    {"name":"Memory","role":"Memory Sync","tagline":"Logs knowledge","system_prompt":"Sync memory","allowed_tools":["company_memory"],"is_qa":false},
                    {"name":"Quality Auditor","role":"QA Inspector","tagline":"Verifies output","system_prompt":"Audit","allowed_tools":["qa_verify"],"is_qa":true}
                ]}"""
            )
        )
        val proposal = engine.proposeCompany("SEO agency")
        val deployed = engine.deployCompany(proposal, "SEO agency")

        // Enqueue deterministic ReAct sequence for handleFounderMessage -> full multi-bot mission:
        // 1) CEO decides to launch full mission with bespoke per-bot mandates aligned to objective & roles
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Q4 SEO Audit","goal":"Research Android SEO, audit GitHub repos, produce PDF + HTML showcase","ceo_reply":"Délégation lancée : Strategist, Builder, Publisher, Memory et QA passent à l'action.","bot_mandates":{"Strategist":"Focus benchmark SEO mobile 2026 via browser_search","Publisher":"Design interactive HTML showcase aligned with NovaSEO brand"}}"""))

        // 2) Strategist calls browser_search (3 search HTTP mocks), then returns spec
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""["seo",["Search engine optimization"],["SEO guide"],["https://en.wikipedia.org/wiki/SEO"]]"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"Heading":"SEO","AbstractText":"Improve rankings","AbstractURL":"https://duckduckgo.com/seo","RelatedTopics":[]}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"hits":[{"title":"Technical SEO 2026","url":"https://example.com/seo","points":80,"num_comments":12}]}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"browser_search","args":{"query":"Technical SEO"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Spec SEO validée : inclure benchmark Wikipédia + HackerNews (Technical SEO 2026) et l'audit des repos GitHub."}"""))

        // 3) Builder calls github_list_repos plugin, then create_pdf, then final
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""[{"full_name":"founder-gh/seo-core","private":false,"url":"https://github.com/founder-gh/seo-core"}]"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"github_list_repos","args":{}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_pdf","args":{"filename":"q4_seo_audit.pdf","title":"Q4 SEO Audit","subtitle":"NovaSEO Autonomous Report","sections":[{"heading":"Market Insights","body":"Technical SEO 2026 findings incorporated cleanly."},{"heading":"GitHub Audit","body":"Inspected repository founder-gh/seo-core with connected GitHub plugin."}]}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","summary":"Q4 SEO Audit PDF created cleanly.","primary_rel_path":"output/q4_seo_audit.pdf"}"""))

        // 4) Publisher (non-Strategist, non-Builder specialist!) calls product_design_prototype, then reports to CEO
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"product_design_prototype","args":{"filename":"seo_dashboard.html","title":"NovaSEO Dashboard","summary":"Interactive HTML showcase synced with Q4 SEO Audit."}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Prototype HTML interactif créé dans output/seo_dashboard.html prêt pour publication."}"""))

        // 5) Quality Auditor audits deliverable
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":95,"report":"PDF valide et prototype HTML vérifiés dans la sandbox."}"""))

        // Run CEO + entire autonomous bot pipeline!
        engine.handleFounderMessage(deployed.id, "Lance un audit SEO Q4 complet avec nos plugins")

        val msgs = db.listMessages(deployed.id)
        val contents = msgs.map { it.content }
        assertTrue("CEO must post assigned roles & prompts on deploy", contents.any { it.contains("Rôles et prompts attribués par le CEO") })
        assertTrue("CEO delegation message missing", contents.any { it.contains("Délégation lancée") })
        val refreshedBots = db.listBots(deployed.id)
        assertTrue(
            "Strategist persisted prompt should carry CEO mission mandate",
            refreshedBots.first { it.name == "Strategist" }.systemPrompt.contains("Focus benchmark SEO mobile 2026"),
        )
        assertTrue("Strategist -> CEO report missing", contents.any { it.contains("[Strategist → CEO]") })
        assertTrue("Builder -> CEO report missing", contents.any { it.contains("[Builder → CEO]") })
        assertTrue("Publisher -> CEO report missing", contents.any { it.contains("[Publisher → CEO]") })
        assertTrue("QA -> CEO report missing", contents.any { it.contains("[Quality Auditor → CEO]") && it.contains("95/100") })
        assertTrue("Memory -> CEO report missing", contents.any { it.contains("[Memory → CEO]") })

        // Verify live telemetry tracked real plugins, tools & URLs for BotInspectorDialog
        val telemetry = engine.botTelemetry.value
        val strategistTel = telemetry.values.firstOrNull { it.botName == "Strategist" }
        assertNotNull("Strategist telemetry missing", strategistTel)
        assertTrue("Strategist should record browser_search trace", strategistTel!!.recentTraces.any { it.tool == "browser_search" && it.success })

        val builderTel = telemetry.values.firstOrNull { it.botName == "Builder" }
        assertNotNull("Builder telemetry missing", builderTel)
        assertTrue("Builder should record github_list_repos trace", builderTel!!.recentTraces.any { it.tool == "github_list_repos" && it.success })
        assertTrue("Builder should record https://github.com URL", builderTel.recentTraces.any { it.url == "https://github.com" })

        val publisherTel = telemetry.values.firstOrNull { it.botName == "Publisher" }
        assertNotNull("Publisher telemetry missing", publisherTel)
        assertTrue("Publisher should record product_design_prototype trace", publisherTel!!.recentTraces.any { it.tool == "product_design_prototype" && it.success })

        // Verify real deliverable files + Human Approval Gate + idempotency
        assertTrue(sandbox.resolveFile(deployed.id, "output/q4_seo_audit.pdf").exists())
        assertTrue(sandbox.resolveFile(deployed.id, "output/seo_dashboard.html").exists())

        val approvals = db.listApprovals(deployed.id)
        assertEquals(1, approvals.size)
        val shipApr = approvals.first()
        assertEquals("PENDING", shipApr.status)

        val approved = engine.decideApproval(shipApr.id, "APPROVED", device = "Unit Test")
        assertEquals("EXECUTED", approved?.status)
        val countBefore = db.listMessages(deployed.id).size
        engine.decideApproval(shipApr.id, "APPROVED", device = "Duplicate Tap")
        assertEquals("Approval decision must be idempotent", countBefore, db.listMessages(deployed.id).size)
    }

    @Test
    fun stopAllExecution_haltsActiveWorkersAndReasoningState() {
        engine.stopAllExecution()
        assertTrue(engine.runCancelled)
        assertFalse(engine.liveReasoning.value.active)
    }

    @Test
    fun freeLlmApi_filtersAvailableFreeModelsAndRunsAutonomousBot() = runTest {
        val freeServer = MockWebServer().apply { start() }
        try {
            val freshLlm = OnDeviceLLMClient(context)
            freshLlm.deleteCustomProvider("mock-ai")
            freshLlm.saveProviderBaseUrl("freellmapi", freeServer.url("").toString().trimEnd('/'))

            // Simulate FreeLLMAPI /v1/models containing auto + available free models + unavailable ones
            freeServer.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "object": "list",
                      "data": [
                        {"id": "dead-offline-model", "name": "Dead Offline", "available": false},
                        {"id": "gemini-3.8-flash", "name": "Gemini 3.8 Flash", "available": true},
                        {"id": "auto", "name": "Auto Free Router", "available": true},
                        {"id": "fusion", "name": "Fusion Panel", "available": true}
                      ]
                    }
                    """.trimIndent()
                )
            )
            val fetchRes = freshLlm.fetchAvailableModels("freellmapi", "freellmapi-test-key")
            assertTrue("FreeLLMAPI fetch should succeed", fetchRes.ok)
            assertEquals(3, fetchRes.models.size)
            assertFalse("Unavailable model must be excluded", fetchRes.models.any { it.id == "dead-offline-model" })
            assertEquals("auto must be pinned first", "auto", fetchRes.models.first().id)
            assertTrue("All FreeLLMAPI models must be tagged free", fetchRes.models.all { it.isFree })
            assertEquals("auto must auto-activate", "auto", freshLlm.getProviderModel("freellmapi"))

            // Verify autonomous engine invokes FreeLLMAPI with Bearer token & free model "auto"
            val freeEngine = AutonomousEngine(
                context = context,
                db = db,
                sandbox = sandbox,
                llm = freshLlm,
                agy = null,
                pluginAuth = auth,
                pluginExecutor = pluginExec,
            )
            val cid = "comp_free_test"
            db.insertCompany(
                com.foundryos.app.data.CompanyItem(
                    id = cid,
                    name = "FreeCo",
                    description = "Run everything on free models",
                    missionStatement = "Deliver SEO and product deliverables at zero API cost",
                    status = "ACTIVE",
                    botsCount = 1,
                    dailyTokenBudget = 100000,
                    tokensUsedToday = 0,
                )
            )
            db.insertBot(
                com.foundryos.app.data.BotItem(
                    id = "b_ceo_free",
                    companyId = cid,
                    name = "CEO",
                    role = "CEO",
                    tagline = "Free model CEO",
                    systemPrompt = "Run company",
                    avatarShape = "circle",
                    avatarColor = "#111111",
                    allowedTools = listOf("browser_search"),
                    model = "auto",
                    isCeo = true,
                    isQa = false,
                )
            )
            freeServer.enqueue(openAiWrap("Analyse terminée gratuitement via FreeLLMAPI (auto)."))

            freeEngine.handleFounderMessage(cid, "Vérifie notre stratégie gratuite")

            val req = freeServer.takeRequest() // models GET
            assertEquals("/v1/models", req.path)
            assertEquals("Bearer freellmapi-test-key", req.getHeader("Authorization"))

            val chatReq = freeServer.takeRequest() // chat POST
            assertEquals("/v1/chat/completions", chatReq.path)
            assertEquals("Bearer freellmapi-test-key", chatReq.getHeader("Authorization"))
            val sentBody = JSONObject(chatReq.body.readUtf8())
            assertEquals("auto", sentBody.getString("model"))

            val msgs = db.listMessages(cid)
            assertTrue("CEO reply via FreeLLMAPI should be saved", msgs.any { it.content.contains("FreeLLMAPI (auto)") })
        } finally {
            freeServer.shutdown()
        }
    }

    @Test
    fun companyLifecycle_supportsArchiveRestoreAndPermanentDeleteWithSandboxWipe() {
        val cid = "cmp_swipe_test"
        db.insertCompany(
            com.foundryos.app.data.CompanyItem(
                id = cid,
                name = "SwipeCo",
                description = "Test swipe archive and delete",
                missionStatement = "Verify lifecycle",
                status = "ACTIVE",
                botsCount = 0,
            )
        )
        sandbox.writeTextFile(cid, "output/note.txt", "temporary")
        assertTrue(sandbox.resolveFile(cid, "output/note.txt").exists())

        // Archive (swipe right)
        db.updateCompanyStatus(cid, "ARCHIVED")
        assertEquals("ARCHIVED", db.listCompanies().first { it.id == cid }.status)

        // Restore (swipe right on archived)
        db.updateCompanyStatus(cid, "ACTIVE")
        assertEquals("ACTIVE", db.listCompanies().first { it.id == cid }.status)

        // Delete (swipe left)
        db.deleteCompany(cid)
        sandbox.destroySandbox(cid)
        assertTrue(db.listCompanies().none { it.id == cid })
        assertFalse(sandbox.resolveFile(cid, "output/note.txt").exists())
    }
}
