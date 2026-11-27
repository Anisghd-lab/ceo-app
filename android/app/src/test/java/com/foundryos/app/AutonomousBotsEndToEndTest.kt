package com.foundryos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foundryos.app.engine.AutonomousEngine
import com.foundryos.app.engine.LocalDatabase
import com.foundryos.app.engine.OnDeviceLLMClient
import com.foundryos.app.engine.OnDeviceSandbox
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginToolExecutor
import kotlinx.coroutines.launch
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
        auth.saveToken("github", "ghp_mock_bot_token", accountLabel = "founder-gh")

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
        val deployed = engine.deployCompany("SEO agency", proposal)

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

        // Stage 3: Quality Auditor audits deliverable before post-QA Publisher stage
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":95,"report":"PDF valide et prêt pour la suite marketing."}"""))

        // Stage 4: Publisher calls product_design_prototype after QA, then reports to CEO
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"product_design_prototype","args":{"filename":"seo_dashboard.html","title":"NovaSEO Dashboard","summary":"Interactive HTML showcase synced with Q4 SEO Audit."}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Prototype HTML interactif créé dans output/seo_dashboard.html prêt pour publication."}"""))

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
        assertTrue("Strategist should record browser_search trace", strategistTel!!.traces.any { it.tool == "browser_search" && it.ok })

        val builderTel = telemetry.values.firstOrNull { it.botName == "Builder" }
        assertNotNull("Builder telemetry missing", builderTel)
        assertTrue("Builder should record github_list_repos trace", builderTel!!.traces.any { it.tool == "github_list_repos" && it.ok })
        assertTrue("Builder should record https://github.com URL", builderTel.traces.any { it.url == "https://github.com" })

        val publisherTel = telemetry.values.firstOrNull { it.botName == "Publisher" }
        assertNotNull("Publisher telemetry missing", publisherTel)
        assertTrue("Publisher should record product_design_prototype trace", publisherTel!!.traces.any { it.tool == "product_design_prototype" && it.ok })

        // Verify real deliverable files + Human Approval Gate + idempotency
        assertTrue(sandbox.resolveFile(deployed.id, "output/q4_seo_audit.pdf").exists())
        assertTrue(sandbox.resolveFile(deployed.id, "output/seo_dashboard.html").exists())

        val approvals = msgs.mapNotNull { it.approval }
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

    @Test
    fun courseCreationCompany_executesFullMultiStageTeamForTrendsDataGraphicsQaMarketingAndSocial() = runTest {
        pluginExec.apiBaseOverrides["instagram"] = pluginServer.url("").toString().trimEnd('/')
        auth.saveToken("instagram", "ig_token_course_live", accountLabel = "@formations_ia")

        // 1) CEO designs the exact 8-role enterprise described by founder
        aiServer.enqueue(
            openAiWrap(
                """{"name":"Atelier Formations IA","mission_statement":"Créer, illustrer, auditer, marketer et vendre des formations tendance.","strategy_summary":"Pipeline multi-étapes 100% autonome.","recommended_connectors":["Instagram","TikTok"],"bots":[
                    {"name":"TrendHunter","role":"Trending Topics Scout","tagline":"Détecte les sujets porteurs","system_prompt":"Trouver les formations demandées via browser_search","allowed_tools":["browser_search"],"is_qa":false},
                    {"name":"DataScout","role":"Data & Curriculum Researcher","tagline":"Collecte sources et data","system_prompt":"Rassembler les faits et modules via browser_search","allowed_tools":["browser_search","browser_read_url"],"is_qa":false},
                    {"name":"VisualStudio","role":"Charts & Graphics Designer","tagline":"Crée graphes et visuels SVG","system_prompt":"Générer des graphiques SVG via create_svg_graphic","allowed_tools":["create_svg_graphic","product_design_prototype"],"is_qa":false},
                    {"name":"CourseBuilder","role":"Course PDF Architect","tagline":"Rédige la formation complète","system_prompt":"Construire le guide PDF via create_pdf","allowed_tools":["create_pdf","sandbox_files"],"is_qa":false},
                    {"name":"QualityAuditor","role":"Independent QA","tagline":"Audite chaque livrable","system_prompt":"Vérifier l'exactitude et la qualité","allowed_tools":["sandbox_files"],"is_qa":true},
                    {"name":"MarketingVideo","role":"Video & Marketing Promo Creator","tagline":"Crée vidéos & pages promo","system_prompt":"Produire une landing/vidéo promo interactive HTML","allowed_tools":["product_design_prototype"],"is_qa":false},
                    {"name":"SocialSales24x7","role":"Instagram/TikTok Growth & Customer Care","tagline":"Répond aux clients & vend 24/7","system_prompt":"Lire les commentaires et préparer réponses/ventes","allowed_tools":["instagram_list_comments","instagram_reply_comment"],"is_qa":false},
                    {"name":"Memory","role":"Company Memory","tagline":"Synchronise l'entreprise","system_prompt":"Conserver les décisions et retours","allowed_tools":["company_memory"],"is_qa":false}
                ]}"""
            )
        )
        val proposal = engine.proposeCompany("Créer des formations avec tendance, data, graphismes, QA, marketing vidéo et suivi Instagram 24/7")
        assertEquals(9, proposal.bots.size) // CEO + 8 specialists preserved!
        val deployed = engine.deployCompany("Formation studio", proposal)

        // 2) Founder triggers full pipeline
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Formation Agentic IA 2026","goal":"Créer et lancer la formation complète","ceo_reply":"Tous les agents passent à l'action étape par étape."}"""))

        // Stage 1a: TrendHunter
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""["ai course",["Generative AI"],["AI trend"],["https://en.wikipedia.org/wiki/AI"]]"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"browser_search","args":{"query":"AI agents course trends 2026"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Tendance n°1 identifiée : Agents IA autonomes pour PME."}"""))

        // Stage 1b: DataScout
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Programme structuré en 4 modules chiffrés (+42% adoption PME)."}"""))

        // Stage 2a: CourseBuilder (Producer)
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_pdf","args":{"filename":"formation_agentic_ia.pdf","title":"Formation Agentic IA 2026","subtitle":"Atelier Formations IA","sections":[{"heading":"Module 1","body":"Architecture multi-agents concrète."},{"heading":"Module 2","body":"Workflows, QA et ventes."}]}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","summary":"Formation PDF prête."}"""))

        // Stage 2b: VisualStudio creates vector SVG chart before QA
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_svg_graphic","args":{"filename":"courbe_marche.svg","title":"Demande Formations IA 2026","summary":"Croissance +42%"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Graphique SVG courbe_marche.svg créé dans output/."}"""))

        // Stage 3: QualityAuditor
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":96,"report":"Formation PDF + visuel SVG conformes."}"""))

        // Stage 4a: MarketingVideo creates HTML5 interactive promo video reel
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"product_design_prototype","args":{"filename":"promo_video_reel.html","title":"Vidéo Promo Formation IA","summary":"Storyboard animé 30s + bouton achat."}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Page/vidéo marketing promo_video_reel.html prête."}"""))

        // Stage 4b: SocialSales24x7 inspects Instagram comments & stages customer reply approval!
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"cmt_99","username":"sara_dev","text":"C'est combien la formation ?"}]}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"instagram_list_comments","args":{"media_id":"media_123"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"instagram_reply_comment","args":{"comment_id":"cmt_99","message":"Bonjour Sara ! La formation est à 149€ avec accès à vie — lien envoyé !"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Commentaire client analysé et réponse commerciale préparée pour approbation."}"""))

        engine.handleFounderMessage(deployed.id, "Lance la création complète et le marketing de notre première formation IA")

        val timeline = db.listMessages(deployed.id).map { it.content }
        assertTrue("TrendHunter must report", timeline.any { it.contains("[TrendHunter → CEO]") })
        assertTrue("DataScout must report", timeline.any { it.contains("[DataScout → CEO]") })
        assertTrue("CourseBuilder must report", timeline.any { it.contains("[CourseBuilder → CEO]") })
        assertTrue("VisualStudio must report", timeline.any { it.contains("[VisualStudio → CEO]") })
        assertTrue("QualityAuditor must report", timeline.any { it.contains("[QualityAuditor → CEO]") })
        assertTrue("MarketingVideo must report", timeline.any { it.contains("[MarketingVideo → CEO]") })
        assertTrue("SocialSales24x7 must report", timeline.any { it.contains("[SocialSales24x7 → CEO]") })
        assertTrue("Memory must report", timeline.any { it.contains("[Memory → CEO]") })

        assertTrue("PDF deliverable must exist", sandbox.resolveFile(deployed.id, "output/formation_agentic_ia.pdf").exists())
        assertTrue("SVG graphic deliverable must exist", sandbox.resolveFile(deployed.id, "output/courbe_marche.svg").exists())
        assertTrue("Promo HTML reel must exist", sandbox.resolveFile(deployed.id, "output/promo_video_reel.html").exists())
        assertTrue(
            "Instagram reply write must be staged for Human Approval alongside final Ship approval",
            db.listMessages(deployed.id).count { it.approval != null } >= 2,
        )
    }

    @Test
    fun watchdogInterrogation_unblocksCompanyWhenBotTimesOutAndAdvancesToNextBot() = runTest {
        aiServer.enqueue(
            openAiWrap(
                """{"name":"ResilientCo","mission_statement":"Deliver goals even if a bot stalls.","strategy_summary":"Watchdog + CEO interrogation.","recommended_connectors":[],"bots":[
                    {"name":"Strategist","role":"Trend Researcher","tagline":"Researches","system_prompt":"Search trends","allowed_tools":["browser_search"],"is_qa":false},
                    {"name":"Builder","role":"Core Builder","tagline":"Builds PDF","system_prompt":"Create PDF","allowed_tools":["create_pdf"],"is_qa":false},
                    {"name":"QualityAuditor","role":"QA","tagline":"Audits","system_prompt":"Audit","allowed_tools":["qa_verify"],"is_qa":true}
                ]}"""
            )
        )
        val proposal = engine.proposeCompany("Resilient startup")
        val deployed = engine.deployCompany("Resilient startup", proposal)

        engine.botStepTimeoutMs = 220L
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Resilient Plan","goal":"Build resilient report","ceo_reply":"Lancement avec surveillance watchdog."}"""))
        // Bot #1 (Strategist) stalls longer than 220ms!
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Too late"}""").setBodyDelay(900, java.util.concurrent.TimeUnit.MILLISECONDS))
        // CEO interrogation of stalled Strategist
        aiServer.enqueue(openAiWrap("""Strategist a expiré sur le réseau ; passage immédiat à Builder pour livrer l'objectif."""))
        // Bot #2 (Builder) executes cleanly
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_pdf","args":{"filename":"resilient.pdf","title":"Resilient Plan","sections":[{"heading":"Plan","body":"Livré malgré le blocage initial."}]}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","summary":"PDF créé."}"""))
        // QA
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":92,"report":"Objectif atteint malgré blocage Bot 1."}"""))

        engine.handleFounderMessage(deployed.id, "Lance la mission")

        val timeline = db.listMessages(deployed.id).map { it.content }
        assertTrue("CEO must interrogate timed-out Strategist", timeline.any { it.contains("[CEO ⇄ Strategist]") && it.contains("Délai dépassé") })
        assertTrue("Builder must still complete after Strategist timeout", timeline.any { it.contains("[Builder → CEO]") })
        assertTrue("QA must validate final deliverable", timeline.any { it.contains("[QualityAuditor → CEO]") && it.contains("92/100") })
        assertTrue("Final deliverable PDF must exist", sandbox.resolveFile(deployed.id, "output/resilient.pdf").exists())
        val feed = sandbox.readTextFile(deployed.id, "workspace/team_feed.md")
        assertTrue("Shared team feed must record interrogation & artifacts", feed.contains("Interrogated Strategist") && feed.contains("resilient.pdf"))
    }

    @Test
    fun stopMidBotAndContinue_interrogatesStoppedBotAndResumesAtNextBot() = runTest {
        aiServer.enqueue(
            openAiWrap(
                """{"name":"ResumeCo","mission_statement":"Resume smoothly after stopping a blocked bot.","strategy_summary":"Stop Bot #2 -> continue -> Bot #3 runs.","recommended_connectors":[],"bots":[
                    {"name":"Strategist","role":"Researcher","tagline":"Scopes","system_prompt":"Scope mission","allowed_tools":["browser_search"],"is_qa":false},
                    {"name":"Builder","role":"Core Builder","tagline":"Builds","system_prompt":"Build deliverable","allowed_tools":["create_pdf"],"is_qa":false},
                    {"name":"Publisher","role":"Growth & Marketing","tagline":"Publishes showcase","system_prompt":"Build HTML launch page","allowed_tools":["product_design_prototype"],"is_qa":false},
                    {"name":"QualityAuditor","role":"QA","tagline":"Audits","system_prompt":"Audit","allowed_tools":["qa_verify"],"is_qa":true}
                ]}"""
            )
        )
        val proposal = engine.proposeCompany("Resume test")
        val deployed = engine.deployCompany("Resume test", proposal)

        // Start mission: Strategist finishes, Builder hangs -> Founder triggers stopAllExecution()
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Launch Kit","goal":"Build launch kit","ceo_reply":"Démarrage équipe."}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Spec validée par Strategist."}"""))
        // Builder hangs until stopped mid-step
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Unreached"}""").setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS))

        val stopTrigger = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            kotlinx.coroutines.delay(350)
            engine.stopAllExecution()
        }
        engine.handleFounderMessage(deployed.id, "Démarre le kit")
        stopTrigger.join()

        val afterStop = db.listMessages(deployed.id).map { it.content }
        assertTrue("Strategist should have finished before stop", afterStop.any { it.contains("[Strategist → CEO]") })
        assertTrue("Stop notice for Builder missing", afterStop.any { it.contains("Builder arrêté") && it.contains("continue") })

        // Now Founder sends "continue" -> CEO interrogates stopped Builder, synthesizes recovery PDF, runs Bot #3 (Publisher) + QA + Approval!
        aiServer.enqueue(openAiWrap("""Builder a été arrêté à mi-parcours ; reprise immédiate sur Publisher pour finaliser."""))
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":91,"report":"Livrable de reprise validé."}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"product_design_prototype","args":{"filename":"launch_bot3.html","title":"Bot 3 Showcase","summary":"Executed immediately after continue!"}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Publisher (Bot 3) a terminé le showcase après reprise."}"""))

        engine.handleFounderMessage(deployed.id, "continue")

        val afterContinue = db.listMessages(deployed.id).map { it.content }
        assertTrue("CEO must interrogate stopped Builder on continue", afterContinue.any { it.contains("[CEO ⇄ Builder]") })
        assertTrue("Bot #3 (Publisher) must run on continue!", afterContinue.any { it.contains("[Publisher → CEO]") })
        assertTrue("Publisher artifact must exist", sandbox.resolveFile(deployed.id, "output/launch_bot3.html").exists())
        assertTrue("Mission approval gate must open after resume", db.listMessages(deployed.id).any { it.approval?.status == "PENDING" })
    }

    @Test
    fun multiToolBatchAndSharedFeed_syncsArtifactsAcrossAllBots() = runTest {
        val cid = "cmp_batch_feed"
        db.insertCompany(
            com.foundryos.app.data.CompanyItem(
                id = cid,
                name = "BatchCo",
                description = "Test direct multi-plugin execution and shared artifact feed",
                missionStatement = "Sync all bots via shared artifacts",
                status = "ACTIVE",
                botsCount = 1,
            )
        )
        val bot = com.foundryos.app.data.BotItem(
            id = "b_ceo_batch",
            companyId = cid,
            name = "CEO",
            role = "CEO",
            tagline = "Direct plugin caller",
            systemPrompt = "Call plugins directly",
            allowedTools = listOf("browser_search", "create_svg_graphic"),
            model = "auto",
            isCeo = true,
            isQa = false,
        )
        db.insertBot(bot)

        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""["batch",["Batch Processing"],["Direct API call"],["https://en.wikipedia.org/wiki/Batch"]]"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        aiServer.enqueue(
            openAiWrap(
                """{"action":"tools","calls":[{"tool":"browser_search","args":{"query":"Batch API"}},{"tool":"create_svg_graphic","args":{"filename":"sync_map.svg","title":"Team Sync Diagram","summary":"Shared visual artifact"}}]}"""
            )
        )
        aiServer.enqueue(openAiWrap("""{"action":"final","reply":"Recherche + graphique SVG créés en parallèle et partagés."}"""))

        engine.handleFounderMessage(cid, "Fais une recherche puis génère le diagramme SVG directement")

        assertTrue("SVG artifact must exist in output/", sandbox.resolveFile(cid, "output/sync_map.svg").exists())
        val feed = sandbox.readTextFile(cid, "workspace/team_feed.md")
        assertTrue("Team feed must contain sync_map.svg for other bots", feed.contains("sync_map.svg"))
        assertTrue("Final CEO answer saved", db.listMessages(cid).any { it.content.contains("partagés") })
    }

    @Test
    fun companyNotionPage_createsOnDeployAndAppendsAcrossWeeks() = runTest {
        auth.saveToken("notion", "secret_notion_123", accountLabel = "Notion Pro")
        pluginExec.apiBaseOverrides["notion"] = pluginServer.url("").toString().trimEnd('/')

        // 1) Propose & Deploy company -> auto-creates dedicated Notion page
        aiServer.enqueue(
            openAiWrap(
                """{"name":"AcademyFlow","mission_statement":"Formations IA en continu","strategy_summary":"Équipe prête.","recommended_connectors":["notion","browser"],"bots":[
                    {"name":"CEO","role":"CEO","tagline":"Leads","system_prompt":"Lead AcademyFlow","allowed_tools":["notion_search"],"is_qa":false},
                    {"name":"Producer","role":"Course Builder","tagline":"Builds PDFs","system_prompt":"Create modules","allowed_tools":["create_pdf"],"is_qa":false},
                    {"name":"Memory","role":"Memory Sync","tagline":"Logs history","system_prompt":"Track journal","allowed_tools":["company_memory"],"is_qa":false},
                    {"name":"Quality Auditor","role":"QA","tagline":"Checks output","system_prompt":"Audit","allowed_tools":["qa_verify"],"is_qa":true}
                ]}"""
            )
        )
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[{"id":"root_ws_page_1"}]}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"notion_cmp_page_99","url":"https://www.notion.so/notioncmppage99"}"""))

        val prop = engine.proposeCompany("Entreprise de formations continues")
        val comp = engine.deployCompany("Entreprise de formations continues", prop)

        val deployMsgs = db.listMessages(comp.id).map { it.content }
        assertTrue("CEO must post created dedicated Notion URL on deploy", deployMsgs.any { it.contains("Page Notion dédiée créée") && it.contains("notion.so") })

        // 2) Week 1 task -> creates module 1 and appends to the same Notion page
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Module 1 IA","goal":"Créer le module 1 PDF","ceo_reply":"Semaine 1 lancée."}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_pdf","args":{"filename":"module_1.pdf","title":"Module 1","subtitle":"Semaine 1","sections":[{"heading":"Intro","body":"Contenu semaine 1 complet."}]}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","summary":"Module 1 prêt.","primary_rel_path":"output/module_1.pdf"}"""))
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":92,"report":"Module 1 validé."}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))

        engine.handleFounderMessage(comp.id, "Tâche d'aujourd'hui : crée le module 1")

        // 3) Next week task on the exact same company -> appends Week 2 to existing Notion page & cumulative journal
        aiServer.enqueue(openAiWrap("""{"action":"run_mission","title":"Module 2 Avancé","goal":"Ajouter le module 2 la semaine suivante","ceo_reply":"Semaine 2 lancée sur le même dossier."}"""))
        aiServer.enqueue(openAiWrap("""{"action":"tool","tool":"create_pdf","args":{"filename":"module_2.pdf","title":"Module 2","subtitle":"Semaine 2","sections":[{"heading":"Pratique","body":"Contenu semaine 2 avec historique."}]}}"""))
        aiServer.enqueue(openAiWrap("""{"action":"final","summary":"Module 2 prêt.","primary_rel_path":"output/module_2.pdf"}"""))
        aiServer.enqueue(openAiWrap("""{"passed":true,"score":96,"report":"Module 2 validé."}"""))
        pluginServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[]}"""))

        engine.handleFounderMessage(comp.id, "Tâche semaine prochaine : ajoute le module 2")

        val journal = sandbox.readTextFile(comp.id, "workspace/company_journal.md")
        assertTrue("Company journal must retain Week 1", journal.contains("Module 1 IA"))
        assertTrue("Company journal must append Week 2", journal.contains("Module 2 Avancé"))

        val searchReq = pluginServer.takeRequest()
        assertEquals("/v1/search", searchReq.path)
        val createReq = pluginServer.takeRequest()
        assertEquals("POST", createReq.method)
        assertEquals("/v1/pages", createReq.path)
        assertTrue(createReq.body.readUtf8().contains("[CEO] AcademyFlow"))

        val patchWeek1 = pluginServer.takeRequest()
        assertEquals("PATCH", patchWeek1.method)
        assertEquals("/v1/blocks/notion_cmp_page_99/children", patchWeek1.path)

        val patchWeek2 = pluginServer.takeRequest()
        assertEquals("PATCH", patchWeek2.method)
        assertEquals("/v1/blocks/notion_cmp_page_99/children", patchWeek2.path)
    }
}
