package com.foundryos.app.engine

import android.content.Context
import android.webkit.MimeTypeMap
import com.foundryos.app.agy.AgyRuntimeManager
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.BoardMeetingData
import com.foundryos.app.data.BotItem
import com.foundryos.app.data.ChatMessageItem
import com.foundryos.app.data.CompanyItem
import com.foundryos.app.data.CompanyProposalData
import com.foundryos.app.service.ApprovalNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 100% On-Device Multi-Company Autonomous Engine (Zero VPS required).
 * Orchestrates: Dynamic CEO Org Design -> Isolated Sandbox Tools -> Real File/PDF Creation
 * -> Independent QA Audit -> Human Approval Gate -> Live HTTP Connector Dispatch.
 */
class AutonomousEngine(
    private val context: Context,
    private val db: LocalDatabase,
    private val sandbox: OnDeviceSandbox,
    private val llm: OnDeviceLLMClient,
    private val agy: AgyRuntimeManager? = null,
) {
    private val prefs = context.getSharedPreferences("ceo_engine_prefs", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var killSwitch: Boolean
        get() = prefs.getBoolean("kill_switch", false)
        set(value) {
            prefs.edit().putBoolean("kill_switch", value).apply()
            if (value) {
                agy?.stopActiveRun()
            }
        }

    fun hasActiveEngine(): Boolean =
        llm.hasAnyKey() || canUseAgyEngine()

    private fun canUseAgyEngine(): Boolean =
        agy != null && agy.preferAgyEngine && agy.installer.isInstalled() && agy.accounts.readLiveToken() != null

    private suspend fun runAiCompletion(
        systemPrompt: String,
        userPrompt: String,
        sandboxDir: File? = null,
        modelSpec: String = "auto",
        jsonMode: Boolean = false,
        maxTokens: Int = 1800,
        onStatus: suspend (String) -> Unit = {},
    ): LLMCallResult {
        if (canUseAgyEngine()) {
            val runtime = agy!!
            val targetDir = sandboxDir ?: runtime.installer.homeDir()
            val prompt = buildString {
                append(systemPrompt.trim())
                if (jsonMode) {
                    append("\n\nCRITICAL: Respond with valid JSON only, without markdown commentary outside JSON.")
                }
                append("\n\nTask:\n")
                append(userPrompt.trim())
            }
            val modelId = when {
                modelSpec.startsWith("agy:", ignoreCase = true) -> modelSpec.removePrefix("agy:").ifBlank { runtime.selectedModel }
                modelSpec.isBlank() || modelSpec == "auto" || ":" in modelSpec -> runtime.selectedModel
                else -> modelSpec
            }
            val (ok, output) = runtime.runPrompt(
                sandboxDir = targetDir,
                prompt = prompt,
                modelId = modelId,
                onStatus = onStatus,
            )
            if (ok && output.isNotBlank()) {
                return LLMCallResult(
                    ok = true,
                    content = output,
                    provider = "antigravity",
                    model = modelId,
                    tokensUsed = (output.length / 4).coerceAtLeast(1),
                )
            }
            if (!llm.hasAnyKey()) {
                return LLMCallResult(ok = false, error = output.ifBlank { "Antigravity CLI failed" })
            }
        }
        return llm.complete(
            messages = listOf("system" to systemPrompt, "user" to userPrompt),
            modelSpec = modelSpec,
            jsonMode = jsonMode,
            maxTokens = maxTokens,
        )
    }

    private val avatarPalette = listOf(
        "triangle" to "#EC4899",
        "hexagon" to "#F97316",
        "pill" to "#10B981",
        "semicircle" to "#2563EB",
        "squircle" to "#7C3AED",
        "diamond" to "#0891B2",
        "star" to "#EAB308",
    )

    private val atomicTools = listOf(
        "sandbox_files",
        "create_pdf",
        "create_zip",
        "web_fetch",
        "company_memory",
        "artifact_publish",
        "http_api_call",
    )

    suspend fun proposeCompany(description: String): CompanyProposalData {
        val prompt = """
            You are the Founder's AI CEO inside an autonomous multi-sandbox Android OS.
            Nothing is hardcoded by industry. The human described a new company to launch:
            "$description"

            Design the exact team of specialist bots (3 to 6 bots, NOT counting the CEO which is added automatically).
            Rules:
            - Output strictly ONE JSON object.
            - MUST include at least ONE independent Quality/Verification bot (`"is_qa": true`) that audits deliverables before human approval.
            - MUST include a Memory bot (`company_memory`) to keep all bots aligned.
            - Available atomic tools per bot: ${JSONArray(atomicTools)}
            - Suggest useful external REST APIs/connectors in `recommended_connectors`.

            JSON schema:
            {
              "name": "Short crisp company name",
              "mission_statement": "1-sentence mission",
              "strategy_summary": "2-sentence strategy explaining how bots build, verify via QA, and ask founder approval",
              "recommended_connectors": ["..."],
              "bots": [
                {"name": "...", "role": "...", "tagline": "4-6 word subtitle", "system_prompt": "...", "allowed_tools": [...], "is_qa": false}
              ]
            }
        """.trimIndent()

        val resp = runAiCompletion(
            systemPrompt = "You are a precise organizational architect. Output valid JSON only.",
            userPrompt = prompt,
            sandboxDir = sandbox.sandboxesBaseDir(),
            jsonMode = true,
            maxTokens = 1800,
        )

        val parsed = if (resp.ok) llm.extractJsonObject(resp.content) else null
        if (parsed != null && parsed.optJSONArray("bots")?.length() ?: 0 >= 2) {
            return normalizeProposal(parsed, description)
        }
        return synthesizeAdaptiveFallback(description)
    }

    private fun normalizeProposal(raw: JSONObject, description: String): CompanyProposalData {
        val name = raw.optString("name").ifBlank { "New Venture" }.take(44)
        val mission = raw.optString("mission_statement").ifBlank { description.take(180) }
        val strategy = raw.optString("strategy_summary").ifBlank {
            "CEO orchestrates work in an isolated sandbox, enforces independent QA verification, and asks your yes before anything ships."
        }
        val connArr = raw.optJSONArray("recommended_connectors") ?: JSONArray()
        val conns = (0 until connArr.length()).map { connArr.optString(it) }.filter { it.isNotBlank() }.take(6)

        val bots = mutableListOf(
            BotItem(
                id = "b_ceo",
                companyId = "",
                name = "CEO",
                role = "Chief Executive & Governor",
                tagline = "Sets goals, hands out work",
                systemPrompt = "You are the CEO of $name. Mission: $mission. Delegate work, enforce QA, and never ship without human approval.",
                avatarShape = "circle",
                avatarColor = "#111111",
                allowedTools = listOf("sandbox_files", "company_memory"),
                isCeo = true,
                isQa = false,
            )
        )

        var hasQa = false
        val rawBots = raw.optJSONArray("bots") ?: JSONArray()
        for (i in 0 until minOf(rawBots.length(), 7)) {
            val b = rawBots.optJSONObject(i) ?: continue
            val bName = b.optString("name").trim()
            if (bName.isBlank() || bName.equals("ceo", ignoreCase = true)) continue
            val (shape, color) = avatarPalette[i % avatarPalette.size]
            val tArr = b.optJSONArray("allowed_tools") ?: JSONArray()
            val tools = (0 until tArr.length()).map { tArr.optString(it) }.filter { it in atomicTools }
                .ifEmpty { listOf("sandbox_files", "create_pdf", "company_memory", "artifact_publish") }
            val isQa = b.optBoolean("is_qa", false)
            if (isQa) hasQa = true
            bots.add(
                BotItem(
                    id = "b_${i + 1}",
                    companyId = "",
                    name = bName.take(32),
                    role = b.optString("role", bName).take(60),
                    tagline = b.optString("tagline", "Executes assigned tasks").take(60),
                    systemPrompt = b.optString("system_prompt", "Execute $bName duties rigorously inside the company sandbox."),
                    avatarShape = shape,
                    avatarColor = color,
                    allowedTools = tools,
                    isCeo = false,
                    isQa = isQa,
                )
            )
        }

        if (!hasQa) {
            val (shape, color) = avatarPalette[bots.size % avatarPalette.size]
            bots.add(
                BotItem(
                    id = "b_qa",
                    companyId = "",
                    name = "Quality Auditor",
                    role = "Independent QA & Verification",
                    tagline = "Audits every deliverable",
                    systemPrompt = "Independently verify quality, accuracy, and completeness of every deliverable before approval.",
                    avatarShape = shape,
                    avatarColor = color,
                    allowedTools = listOf("sandbox_files", "company_memory"),
                    isQa = true,
                )
            )
        }

        return CompanyProposalData(
            name = name,
            missionStatement = mission,
            strategySummary = strategy,
            recommendedConnectors = conns,
            bots = bots,
            rawProposalJson = raw.toString(),
        )
    }

    private fun synthesizeAdaptiveFallback(description: String): CompanyProposalData {
        val clean = description.trim()
        val words = clean.split(Regex("\\s+")).map { it.trim('.', ',', '!', '?', ';', ':', '"', '\'') }.filter { it.length > 3 }
        val name = if (words.isNotEmpty()) words.take(3).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.take(34) else "Autonomous Venture"
        val raw = JSONObject()
            .put("name", name)
            .put("mission_statement", clean.take(180))
            .put(
                "strategy_summary",
                "Dedicated isolated sandbox for '$name'. Strategist scopes deliverables, Builder produces real files on-device, Quality Auditor verifies them, and CEO asks your approval before publishing.",
            )
            .put("recommended_connectors", JSONArray(listOf("Shopify / WooCommerce API", "GitHub API", "Stripe / Custom Webhook")))
            .put(
                "bots",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("name", "Strategist")
                            .put("role", "Research & Specification")
                            .put("tagline", "Turns goals into specs")
                            .put("system_prompt", "Research and turn goals for '$clean' into detailed actionable specifications.")
                            .put("allowed_tools", JSONArray(listOf("web_fetch", "sandbox_files", "company_memory")))
                            .put("is_qa", false),
                        JSONObject()
                            .put("name", "Creator")
                            .put("role", "Core Deliverable Production")
                            .put("tagline", "Builds verified assets")
                            .put("system_prompt", "Produce high-value deliverables (PDFs, guides, templates, code, bundles) for '$clean' inside output/.")
                            .put("allowed_tools", JSONArray(listOf("sandbox_files", "create_pdf", "create_zip", "artifact_publish", "company_memory")))
                            .put("is_qa", false),
                        JSONObject()
                            .put("name", "Quality Auditor")
                            .put("role", "Independent QA")
                            .put("tagline", "Verifies every deliverable")
                            .put("system_prompt", "Strictly audit deliverable quality, structure, and completeness before human approval.")
                            .put("allowed_tools", JSONArray(listOf("sandbox_files", "company_memory")))
                            .put("is_qa", true),
                        JSONObject()
                            .put("name", "Publisher")
                            .put("role", "Distribution & Launch")
                            .put("tagline", "Prepares listings & launch")
                            .put("system_prompt", "Prepare store/release listings and dispatch approved API calls.")
                            .put("allowed_tools", JSONArray(listOf("sandbox_files", "http_api_call", "artifact_publish", "company_memory")))
                            .put("is_qa", false),
                        JSONObject()
                            .put("name", "Memory")
                            .put("role", "Shared Company Knowledge")
                            .put("tagline", "Keeps everyone aligned")
                            .put("system_prompt", "Record decisions, specs, and results so bots never duplicate work.")
                            .put("allowed_tools", JSONArray(listOf("company_memory", "sandbox_files")))
                            .put("is_qa", false),
                    )
                ),
            )
        return normalizeProposal(raw, description)
    }

    suspend fun deployCompany(description: String, proposal: CompanyProposalData): CompanyItem = withContext(Dispatchers.IO) {
        val cid = "cmp_${UUID.randomUUID().toString().take(10)}"
        sandbox.companyRoot(cid)

        val comp = CompanyItem(
            id = cid,
            name = proposal.name,
            description = description,
            missionStatement = proposal.missionStatement,
            status = "ACTIVE",
            botsCount = proposal.bots.size,
        )
        db.insertCompany(comp)

        val persistedBots = proposal.bots.mapIndexed { idx, b ->
            val bot = b.copy(
                id = "bot_${cid}_$idx",
                companyId = cid,
                status = "idle",
                statusText = b.tagline,
            )
            db.insertBot(bot)
            bot
        }

        db.rememberNote("mem_${UUID.randomUUID().toString().take(8)}", cid, "Memory", "founding", "Company '${proposal.name}' founded: ${proposal.missionStatement}")

        db.insertMessage(ChatMessageItem("msg_${UUID.randomUUID().toString().take(10)}", cid, "human", content = description))
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                cid,
                "bot",
                botName = "CEO",
                content = "Hiring your company: ${persistedBots.size} bots and one human, you. Nothing ships without your yes.",
            )
        )

        val orgPayload = JSONObject()
            .put("company_name", proposal.name)
            .put("subtitle", "1 human · ${persistedBots.size} bots · isolated on-device sandbox")
            .put("recommended_connectors", JSONArray(proposal.recommendedConnectors))
            .put(
                "bots",
                JSONArray(
                    persistedBots.map { b ->
                        JSONObject()
                            .put("id", b.id)
                            .put("name", b.name)
                            .put("role", b.role)
                            .put("tagline", b.tagline)
                            .put("avatar_shape", b.avatarShape)
                            .put("avatar_color", b.avatarColor)
                            .put("allowed_tools", JSONArray(b.allowedTools))
                            .put("is_ceo", b.isCeo)
                            .put("is_qa", b.isQa)
                    }
                ),
            )
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                cid,
                "bot",
                botName = "CEO",
                cardType = "company_org",
            ),
            rawPayloadJson = orgPayload.toString(),
        )

        comp.copy(bots = persistedBots)
    }

    suspend fun handleFounderMessage(
        companyId: String,
        text: String,
        attachmentsSummary: String = "",
        displayMessage: String = text,
        targetBotId: String? = null,
        onStatusUpdate: suspend (String) -> Unit = {},
    ) {
        val comp = db.getCompany(companyId) ?: return
        db.insertMessage(ChatMessageItem("msg_${UUID.randomUUID().toString().take(10)}", companyId, "human", content = displayMessage))

        if (killSwitch) {
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    companyId,
                    "bot",
                    botName = "CEO",
                    content = "Global Kill Switch is active. Turn it off in Settings to resume bot execution.",
                )
            )
            return
        }

        val enrichedPrompt = if (attachmentsSummary.isBlank()) text else "$text\n\n$attachmentsSummary"

        val bots = db.listBots(companyId)
        if (targetBotId != null) {
            val targetBot = bots.firstOrNull { it.id == targetBotId }
            if (targetBot != null && !targetBot.isCeo) {
                onStatusUpdate("${targetBot.name} is thinking...")
                db.updateBotStatus(targetBot.id, "working", "Handling your message...")
                val reply = runDirectBotChat(comp, targetBot, enrichedPrompt)
                db.updateBotStatus(targetBot.id, "idle", reply.take(50))
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        companyId,
                        "bot",
                        botId = targetBot.id,
                        botName = targetBot.name,
                        content = reply,
                    )
                )
                return
            }
        }

        val lower = text.lowercase()
        if (listOf("board meeting", "bilan", "rapport", "report", "metrics", "kpi").any { it in lower }) {
            generateBoardMeeting(companyId)
            return
        }

        runFullMission(comp, bots, title = text.ifBlank { "Analyze attached files" }.take(58), goal = enrichedPrompt, onStatusUpdate = onStatusUpdate)
    }

    private suspend fun runDirectBotChat(comp: CompanyItem, bot: BotItem, userText: String): String {
        val mem = db.recallNotes(comp.id, 6).joinToString("\n")
        val files = sandbox.listFiles(comp.id).joinToString(", ") { "${it.first} (${it.second}B)" }
        val res = runAiCompletion(
            systemPrompt = "You are ${bot.name} (${bot.role}) at '${comp.name}'. Instructions: ${bot.systemPrompt}\nSandbox files: ${files.ifBlank { "none" }}\nCompany memory:\n$mem",
            userPrompt = userText,
            sandboxDir = sandbox.companyRoot(comp.id),
            modelSpec = bot.model,
            maxTokens = 1200,
        )
        if (res.ok) {
            db.addTokensUsed(comp.id, res.tokensUsed)
            return res.content
        }
        return if (res.isOffline) {
            "No AI engine active yet. Open Settings (gear icon) to sign into Antigravity CLI with Google or save an LLM API key."
        } else {
            "AI error: ${res.error}"
        }
    }

    private suspend fun runFullMission(
        comp: CompanyItem,
        bots: List<BotItem>,
        title: String,
        goal: String,
        onStatusUpdate: suspend (String) -> Unit,
    ) {
        val ceoBot = bots.firstOrNull { it.isCeo } ?: bots.first()
        val qaBot = bots.firstOrNull { it.isQa } ?: bots.last()
        val strategistBot = bots.firstOrNull { !it.isCeo && !it.isQa && (it.allowedTools.contains("web_fetch") || it.name.contains("Strateg", true)) }
        val producerBot = bots.firstOrNull { !it.isCeo && !it.isQa && (it.allowedTools.contains("create_pdf") || it.id != strategistBot?.id) }
            ?: bots.firstOrNull { !it.isCeo && !it.isQa } ?: ceoBot

        val missionId = "msn_${UUID.randomUUID().toString().take(8)}"
        db.insertMission(missionId, comp.id, title, goal)
        db.updateBotStatus(ceoBot.id, "working", "Delegating mission...")

        // Step 1: Strategist / Scoping (if available and AI engine active)
        var scopedSpec = goal
        if (strategistBot != null && hasActiveEngine()) {
            onStatusUpdate("${strategistBot.name} is scoping...")
            db.updateBotStatus(strategistBot.id, "working", "Writing spec...")
            val specRes = runAiCompletion(
                systemPrompt = "You are ${strategistBot.name} (${strategistBot.role}) at '${comp.name}'. Produce a sharp structured outline & quality criteria for the deliverable.",
                userPrompt = "Company: ${comp.description}\nMission: $goal",
                sandboxDir = sandbox.companyRoot(comp.id),
                modelSpec = strategistBot.model,
                maxTokens = 1000,
                onStatus = onStatusUpdate,
            )
            if (specRes.ok) {
                scopedSpec = specRes.content
                db.addTokensUsed(comp.id, specRes.tokensUsed)
                sandbox.writeTextFile(comp.id, "workspace/${missionId}_spec.md", scopedSpec)
                db.updateBotStatus(strategistBot.id, "idle", "Spec done", incrementUnread = true)
            } else {
                db.updateBotStatus(strategistBot.id, "idle", "Ready")
            }
        }

        // Step 2: Producer Bot runs ReAct Tool Loop inside isolated Android sandbox
        onStatusUpdate("${producerBot.name} is building in sandbox...")
        db.updateBotStatus(producerBot.id, "working", "Building deliverable...")

        val createdArtifacts = runProducerAgentLoop(comp, producerBot, missionId, title, goal, scopedSpec)
        db.updateBotStatus(producerBot.id, "idle", "Deliverable ready", incrementUnread = true)

        // Step 3: Independent Quality Auditor checks every artifact
        onStatusUpdate("${qaBot.name} is verifying quality...")
        db.updateBotStatus(qaBot.id, "working", "Auditing in sandbox...")

        val primaryArt = createdArtifacts.firstOrNull()
        val (qaPassed, qaScore, qaReport) = runIndependentQa(comp, qaBot, primaryArt, goal)
        db.updateBotStatus(qaBot.id, "idle", "QA $qaScore/100", incrementUnread = true)

        for (art in createdArtifacts) {
            val status = if (qaPassed) "PASSED" else "FAILED"
            db.updateArtifactQa(art.id, status, qaScore, qaReport)
            val updatedArt = art.copy(qaStatus = status, qaScore = qaScore, qaReport = qaReport)
            val artPayload = JSONObject()
                .put("id", updatedArt.id)
                .put("bot_name", updatedArt.botName)
                .put("title", updatedArt.title)
                .put("filename", updatedArt.filename)
                .put("rel_path", updatedArt.relPath)
                .put("mime_type", updatedArt.mimeType)
                .put("size_bytes", updatedArt.sizeBytes)
                .put("summary", updatedArt.summary)
                .put("qa_status", updatedArt.qaStatus)
                .put("qa_score", updatedArt.qaScore)
                .put("qa_report", updatedArt.qaReport)
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    comp.id,
                    "bot",
                    botId = producerBot.id,
                    botName = producerBot.name,
                    content = "${producerBot.name} prepared deliverable",
                    cardType = "artifact",
                ),
                rawPayloadJson = artPayload.toString(),
            )
        }

        db.rememberNote(
            "mem_${UUID.randomUUID().toString().take(8)}",
            comp.id,
            "Memory",
            "mission",
            "Mission '$title' built by ${producerBot.name}; QA by ${qaBot.name}: $qaScore/100 ($qaReport)",
        )

        // Step 4: CEO opens Human Approval Gate + Android Notification
        db.updateMissionStatus(missionId, "PENDING_APPROVAL", qaScore)
        val aprId = "apr_${UUID.randomUUID().toString().take(10)}"
        val aprPayload = JSONObject()
            .put("artifact_id", primaryArt?.id ?: "")
            .put("filename", primaryArt?.filename ?: "")
            .put("completion_message", "Shipped! Team and memory synced for '$title'.")
        val approval = ApprovalItem(
            id = aprId,
            companyId = comp.id,
            missionId = missionId,
            artifactId = primaryArt?.id,
            title = "Ship ${primaryArt?.title ?: title}?",
            subtitle = "QA $qaScore/100 by ${qaBot.name} · Verified in on-device sandbox",
            actionType = "SHIP_RELEASE",
            actionPayloadJson = aprPayload.toString(),
            status = "PENDING",
        )
        db.insertApproval(approval)
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                comp.id,
                "bot",
                botName = "CEO",
                content = if (hasActiveEngine()) "${primaryArt?.title ?: title} is ready. Shipping it needs your yes."
                else "${primaryArt?.title ?: title} generated in local preview (connect Google in Antigravity CLI or add an LLM key in Settings for AI-authored content). Shipping needs your yes.",
                cardType = "approval",
            ),
            rawPayloadJson = JSONObject().put("id", aprId).toString(),
        )

        db.updateBotStatus(ceoBot.id, "idle", "Waiting for your yes")
        ApprovalNotifier.showApprovalNotification(context, approval)
    }

    private suspend fun runProducerAgentLoop(
        comp: CompanyItem,
        bot: BotItem,
        missionId: String,
        title: String,
        goal: String,
        spec: String,
    ): List<ArtifactItem> {
        val created = mutableListOf<ArtifactItem>()
        if (!hasActiveEngine()) {
            created.add(buildFallbackPdfArtifact(comp, bot, title, goal, spec))
            return created
        }

        val compRoot = sandbox.companyRoot(comp.id)
        val existingOutNames = sandbox.listOutputFiles(comp.id).map { it.name }.toSet()

        if (canUseAgyEngine()) {
            // Antigravity CLI natively executes multi-step reasoning + file tools inside compRoot
            val agyRes = runAiCompletion(
                systemPrompt = """
                    You are ${bot.name} (${bot.role}) at company '${comp.name}'.
                    Company mission: ${comp.description}
                    Your instructions: ${bot.systemPrompt}

                    Work inside this sandbox directory (${compRoot.absolutePath}).
                    Create high-quality deliverable files inside output/ (e.g. output/${slugify(title)}.md or .html or .csv or scripts) AND/OR return a single JSON object:
                    {"action": "tool", "tool": "create_pdf", "args": {"filename": "${slugify(title)}.pdf", "title": "...", "subtitle": "...", "sections": [{"heading": "...", "body": "..."}]}}
                    If you already wrote deliverable files directly into output/, respond with:
                    {"title": "...", "subtitle": "...", "sections": [{"heading": "...", "body": "..."}]} so we also generate a crisp PDF companion.
                """.trimIndent(),
                userPrompt = "Mission: $title\nGoal: $goal\nSpec:\n$spec",
                sandboxDir = compRoot,
                modelSpec = bot.model,
                maxTokens = 3200,
            )
            if (agyRes.ok) {
                db.addTokensUsed(comp.id, agyRes.tokensUsed)
                // 1) Pick up any new real files Antigravity CLI wrote directly into output/
                val newFiles = sandbox.listOutputFiles(comp.id).filter { it.name !in existingOutNames }
                for (f in newFiles) {
                    created.add(registerArtifact(comp.id, bot.name, f.nameWithoutExtension, f, "Generated in sandbox via Antigravity CLI"))
                }
                // 2) Check if Antigravity CLI returned JSON tool / sections to compile a PDF
                val parsed = llm.extractJsonObject(agyRes.content)
                if (parsed != null) {
                    if (parsed.optString("tool") == "create_pdf") {
                        val args = parsed.optJSONObject("args") ?: JSONObject()
                        executeSandboxTool(comp, bot, "create_pdf", args).second?.let { created.add(it) }
                    } else if (parsed.has("sections")) {
                        executeSandboxTool(comp, bot, "create_pdf", parsed.put("filename", "${slugify(title)}.pdf")).second?.let { created.add(it) }
                    }
                } else if (created.isEmpty() && agyRes.content.length > 80) {
                    // Turn Antigravity CLI markdown/text response into a structured PDF artifact
                    val file = sandbox.createPdf(
                        companyId = comp.id,
                        filename = "${slugify(title)}.pdf",
                        title = title,
                        subtitle = "${comp.name} · Generated via Antigravity CLI (${bot.name})",
                        sections = listOf(
                            "Deliverable" to agyRes.content.take(12000),
                        ),
                    )
                    created.add(registerArtifact(comp.id, bot.name, title, file, "PDF generated via Antigravity CLI"))
                }
            }
        }

        if (created.isEmpty() && llm.hasAnyKey()) {
            val sysPrompt = """
                You are ${bot.name} (${bot.role}) at company '${comp.name}'.
                Company mission: ${comp.description}
                Your instructions: ${bot.systemPrompt}

                You work inside an isolated Android sandbox. You MUST reply strictly with ONE JSON object:
                Option 1 (call a tool):
                {"action": "tool", "tool": "create_pdf", "args": {"filename": "slug.pdf", "title": "...", "subtitle": "...", "sections": [{"heading": "...", "body": "..."}]}}
                Other tools:
                - "list_files": {}
                - "read_file": {"rel_path": "workspace/uploads/file.txt"}
                - "write_file": {"rel_path": "output/file.md", "content": "..."}
                - "create_zip": {"zip_filename": "bundle.zip", "rel_paths": ["output/file1.pdf", "output/file2.md"]}
                - "web_fetch": {"url": "https://..."}
                Option 2 (finish after files are created):
                {"action": "final", "summary": "...", "primary_rel_path": "output/slug.pdf"}

                Make sure the content inside create_pdf / write_file is genuinely thorough, rich, actionable, and publish-ready — never shallow placeholders.
            """.trimIndent()

            val history = mutableListOf(
                "system" to sysPrompt,
                "user" to "Mission: $title\nGoal: $goal\nSpec:\n$spec",
            )

            for (step in 1..4) {
                if (killSwitch) break
                val res = llm.complete(history, modelSpec = bot.model, jsonMode = true, maxTokens = 3200)
                if (!res.ok) break
                db.addTokensUsed(comp.id, res.tokensUsed)
                val parsed = llm.extractJsonObject(res.content) ?: break
                val action = parsed.optString("action", "final").lowercase()
                if (action == "tool") {
                    val tool = parsed.optString("tool")
                    val args = parsed.optJSONObject("args") ?: JSONObject()
                    val toolRes = executeSandboxTool(comp, bot, tool, args)
                    val art = toolRes.second
                    if (art != null) created.add(art)
                    history.add("assistant" to parsed.toString())
                    history.add("user" to "Tool '$tool' result: ${toolRes.first}")
                } else {
                    break
                }
            }
        }

        if (created.isEmpty()) {
            val secRes = runAiCompletion(
                systemPrompt = "Write high-quality deliverable content as JSON: {\"title\": \"...\", \"subtitle\": \"...\", \"sections\": [{\"heading\": \"...\", \"body\": \"...\"}]} (at least 4 detailed sections).",
                userPrompt = "Company: ${comp.name}\nGoal: $goal",
                sandboxDir = compRoot,
                modelSpec = bot.model,
                jsonMode = true,
                maxTokens = 3000,
            )
            val obj = if (secRes.ok) llm.extractJsonObject(secRes.content) else null
            if (obj != null) {
                db.addTokensUsed(comp.id, secRes.tokensUsed)
                val toolRes = executeSandboxTool(comp, bot, "create_pdf", obj.put("filename", "${slugify(title)}.pdf"))
                toolRes.second?.let { created.add(it) }
            }
        }

        if (created.isEmpty()) {
            created.add(buildFallbackPdfArtifact(comp, bot, title, goal, spec))
        }
        return created
    }

    private suspend fun executeSandboxTool(
        comp: CompanyItem,
        bot: BotItem,
        tool: String,
        args: JSONObject,
    ): Pair<String, ArtifactItem?> {
        return when (tool) {
            "create_pdf" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}.pdf" }
                val docTitle = args.optString("title").ifBlank { comp.name }
                val sub = args.optString("subtitle").ifBlank { "Produced by ${bot.name} · ${comp.name}" }
                val secArr = args.optJSONArray("sections") ?: JSONArray()
                val sections = mutableListOf<Pair<String, String>>()
                for (i in 0 until secArr.length()) {
                    val s = secArr.optJSONObject(i) ?: continue
                    sections.add(s.optString("heading", "Section ${i + 1}") to s.optString("body", ""))
                }
                if (sections.isEmpty()) {
                    sections.add("Overview" to args.optString("content", comp.description))
                }
                val file = sandbox.createPdf(comp.id, fn, docTitle, sub, sections)
                val art = registerArtifact(comp.id, bot.name, docTitle, file, "PDF deliverable (${sections.size} sections)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "list_files" -> {
                val files = sandbox.listFiles(comp.id).joinToString("\n") { "${it.first} (${it.second} bytes)" }
                JSONObject().put("ok", true).put("files", files).toString() to null
            }
            "read_file" -> {
                val rel = args.optString("rel_path")
                val content = runCatching { sandbox.readTextFile(comp.id, rel) }.getOrElse { it.message ?: "Read error" }
                JSONObject().put("ok", true).put("content", content).toString() to null
            }
            "write_file" -> {
                val rel = args.optString("rel_path").ifBlank { "output/deliverable.md" }
                val content = args.optString("content", "")
                val file = sandbox.writeTextFile(comp.id, rel, content)
                val art = if (rel.startsWith("output/")) {
                    registerArtifact(comp.id, bot.name, file.nameWithoutExtension, file, "Created in sandbox")
                } else null
                JSONObject().put("ok", true).put("rel_path", rel).put("bytes", file.length()).toString() to art
            }
            "create_zip" -> {
                val zName = args.optString("zip_filename").ifBlank { "${slugify(comp.name)}_pack.zip" }
                val arr = args.optJSONArray("rel_paths") ?: JSONArray()
                val paths = (0 until arr.length()).map { arr.optString(it) }
                val file = sandbox.createZipBundle(comp.id, zName, paths)
                val art = registerArtifact(comp.id, bot.name, zName, file, "Packaged ZIP (${paths.size} files)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "web_fetch" -> {
                val url = args.optString("url")
                if (!url.startsWith("http")) return JSONObject().put("ok", false).put("error", "Invalid URL").toString() to null
                try {
                    val req = Request.Builder().url(url).header("User-Agent", "CEO-Android/0.2").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        val preview = (resp.body?.string() ?: "").take(3000)
                        JSONObject().put("ok", resp.isSuccessful).put("status", resp.code).put("preview", preview).toString() to null
                    }
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message).toString() to null
                }
            }
            else -> JSONObject().put("ok", false).put("error", "Unknown tool: $tool").toString() to null
        }
    }

    private fun registerArtifact(companyId: String, botName: String, title: String, file: File, summary: String): ArtifactItem {
        val root = sandbox.companyRoot(companyId)
        val relPath = file.relativeTo(root).path
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "md" -> "text/markdown"
            else -> "text/plain"
        }
        val art = ArtifactItem(
            id = "art_${UUID.randomUUID().toString().take(10)}",
            companyId = companyId,
            botName = botName,
            title = title.take(64),
            filename = file.name,
            relPath = relPath,
            mimeType = mime,
            sizeBytes = file.length(),
            summary = summary,
            qaStatus = "PENDING",
        )
        db.insertArtifact(art)
        return art
    }

    private fun buildFallbackPdfArtifact(comp: CompanyItem, bot: BotItem, title: String, goal: String, spec: String): ArtifactItem {
        val file = sandbox.createPdf(
            companyId = comp.id,
            filename = "${slugify(title)}.pdf",
            title = title,
            subtitle = "${comp.name} · Generated in on-device sandbox by ${bot.name}",
            sections = listOf(
                "Mission Brief" to goal,
                "Scope & Architecture" to spec,
                "Quality & Publishing Notes" to "This deliverable was generated directly inside your Android sandbox (${comp.id}). Add an LLM API key in Settings to unlock full AI authoring.",
            ),
        )
        return registerArtifact(comp.id, bot.name, title, file, "On-device PDF deliverable")
    }

    private suspend fun runIndependentQa(
        comp: CompanyItem,
        qaBot: BotItem,
        artifact: ArtifactItem?,
        goal: String,
    ): Triple<Boolean, Int, String> {
        if (artifact == null) return Triple(false, 30, "No deliverable produced in sandbox")
        val prog = sandbox.verifyArtifactProgrammatically(comp.id, artifact.relPath)
        if (!prog.passed || !hasActiveEngine()) {
            return Triple(prog.passed, prog.score, prog.report)
        }
        val res = runAiCompletion(
            systemPrompt = "You are ${qaBot.name} (${qaBot.role}). Audit the deliverable and reply ONLY as JSON: {\"passed\": true, \"score\": 92, \"report\": \"short verdict\"}.",
            userPrompt = "Goal: $goal\nFile: ${artifact.filename} (${artifact.sizeBytes} bytes)\nProgrammatic check: ${prog.report}",
            sandboxDir = sandbox.companyRoot(comp.id),
            modelSpec = qaBot.model,
            jsonMode = true,
            maxTokens = 400,
        )
        val obj = if (res.ok) llm.extractJsonObject(res.content) else null
        return if (obj != null) {
            db.addTokensUsed(comp.id, res.tokensUsed)
            val passed = obj.optBoolean("passed", prog.passed) && prog.passed
            val score = obj.optInt("score", prog.score).coerceIn(0, 100)
            val report = obj.optString("report").ifBlank { prog.report }
            Triple(passed, score, report)
        } else {
            Triple(prog.passed, prog.score, prog.report)
        }
    }

    suspend fun decideApproval(approvalId: String, decision: String, device: String = "Android", feedback: String = ""): ApprovalItem? =
        withContext(Dispatchers.IO) {
            val norm = decision.uppercase().trim()
            if (norm != "APPROVED" && norm != "REJECTED") return@withContext null
            val claimed = db.claimApprovalDecision(approvalId, norm, device, feedback)
            val existing = db.getApproval(approvalId) ?: return@withContext null
            if (!claimed) return@withContext existing

            if (norm == "APPROVED") {
                val payload = runCatching { JSONObject(existing.actionPayloadJson) }.getOrDefault(JSONObject())
                val note = executeApprovedAction(existing.companyId, existing.actionType, payload)
                db.finalizeApprovalExecution(approvalId, "EXECUTED", note)
                existing.missionId?.let { db.updateMissionStatus(it, "COMPLETED") }
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        existing.companyId,
                        "system",
                        botName = "CEO",
                        content = "You approved ${existing.title} on $device",
                        cardType = "approval_confirmed",
                    )
                )
                db.rememberNote(
                    "mem_${UUID.randomUUID().toString().take(8)}",
                    existing.companyId,
                    "Memory",
                    "decision",
                    "Founder approved '${existing.title}' on $device ($note)",
                )
            } else {
                existing.missionId?.let { db.updateMissionStatus(it, "REJECTED") }
                db.finalizeApprovalExecution(approvalId, "REJECTED", "Sent back for revision")
            }
            db.getApproval(approvalId)
        }

    private fun executeApprovedAction(companyId: String, actionType: String, payload: JSONObject): String {
        if (actionType == "HTTP_ACTION") {
            val connName = payload.optString("connector")
            val conn = db.listConnectors(companyId).firstOrNull { it.name.equals(connName, ignoreCase = true) }
            if (conn == null) return "Connector '$connName' not attached; export file manually."
            if (!conn.liveEnabled) return "Dry-run logged for ${conn.name} (enable Live Calls in connector settings)."
            val url = conn.baseUrl.trimEnd('/') + "/" + payload.optString("path", "").trimStart('/')
            val method = payload.optString("method", "POST").uppercase()
            val bodyStr = payload.optJSONObject("body")?.toString() ?: "{}"
            return try {
                val req = Request.Builder()
                    .url(url)
                    .apply { if (conn.authValue.isNotBlank()) header(conn.authHeader, conn.authValue) }
                    .method(method, bodyStr.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    "Live API HTTP ${resp.code} from ${conn.name}"
                }
            } catch (e: Exception) {
                "API error: ${e.message}"
            }
        }
        return payload.optString("completion_message").ifBlank { "Shipped from on-device sandbox." }
    }

    fun generateBoardMeeting(companyId: String): BoardMeetingData {
        val board = db.computeBoardMeeting(companyId)
        val payload = JSONObject()
            .put("title", board.title)
            .put("sources_count", board.sourcesCount)
            .put("shipped", board.shipped)
            .put("missions", board.missions)
            .put("qa_passed", board.qaPassed)
            .put("decisions_approved", board.decisionsApproved)
            .put("tokens_used", board.tokensUsed)
            .put("token_budget", board.tokenBudget)
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                companyId,
                "bot",
                botName = "CEO",
                content = "Ready for the board.",
                cardType = "board_meeting",
                boardMeeting = board,
            ),
            rawPayloadJson = payload.toString(),
        )
        return board
    }

    private fun slugify(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(28).ifBlank { "deliverable" }
}
