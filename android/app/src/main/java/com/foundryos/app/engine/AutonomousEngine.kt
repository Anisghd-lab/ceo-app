package com.foundryos.app.engine

import android.content.Context
import android.webkit.MimeTypeMap
import com.foundryos.app.agy.AgyRuntimeManager
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.BoardMeetingData
import com.foundryos.app.data.BotActionTrace
import com.foundryos.app.data.BotItem
import com.foundryos.app.data.BotLiveActivity
import com.foundryos.app.data.ChatMessageItem
import com.foundryos.app.data.ChoiceOption
import com.foundryos.app.data.ChoicePromptData
import com.foundryos.app.data.CompanyItem
import com.foundryos.app.data.CompanyProposalData
import com.foundryos.app.data.LiveReasoningState
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginRegistry
import com.foundryos.app.plugins.PluginToolExecutor
import com.foundryos.app.service.ApprovalNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val pluginAuth: PluginAuthManager? = null,
    private val pluginExecutor: PluginToolExecutor? = null,
) {
    private val prefs = context.getSharedPreferences("ceo_engine_prefs", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _botTelemetry = MutableStateFlow<Map<String, BotLiveActivity>>(emptyMap())
    val botTelemetry: StateFlow<Map<String, BotLiveActivity>> = _botTelemetry.asStateFlow()

    private val _liveReasoning = MutableStateFlow(LiveReasoningState())
    val liveReasoning: StateFlow<LiveReasoningState> = _liveReasoning.asStateFlow()

    @Volatile
    var runCancelled: Boolean = false
        private set

    internal var botStepTimeoutMs: Long = 180_000L

    @Volatile
    private var currentMissionCompanyId: String? = null

    @Volatile
    private var currentRunningBotId: String? = null

    data class MissionCheckpoint(
        val missionId: String,
        val title: String,
        val goal: String,
        val scopedSpec: String,
        val completedBotIds: MutableSet<String> = mutableSetOf(),
        val interruptedBotId: String? = null,
        val botMandates: Map<String, String> = emptyMap(),
    )

    var killSwitch: Boolean
        get() = prefs.getBoolean("kill_switch", false)
        set(value) {
            prefs.edit().putBoolean("kill_switch", value).apply()
            if (value) {
                stopAllExecution()
            }
        }

    fun resetCancellation() {
        runCancelled = false
    }

    fun stopAllExecution() {
        runCancelled = true
        agy?.stopActiveRun()
        val activeCid = currentMissionCompanyId
        val activeBotId = currentRunningBotId
        if (activeCid != null && activeBotId != null) {
            val ckpt = loadMissionCheckpoint(activeCid)
            if (ckpt != null) {
                saveMissionCheckpoint(activeCid, ckpt.copy(interruptedBotId = activeBotId))
            }
        }
        val updated = _botTelemetry.value.mapValues { (_, act) ->
            if (act.status == "working") act.copy(status = "stopped", currentTask = "Stopped by Founder", updatedAtMs = System.currentTimeMillis()) else act
        }
        _botTelemetry.value = updated
        val cur = _liveReasoning.value
        if (cur.active) {
            _liveReasoning.value = cur.copy(
                active = false,
                statusLine = "Stopped by Founder",
                lastCompletedSummary = "Stopped after ${(System.currentTimeMillis() - cur.startedAtMs) / 1000}s",
                lastThinkingText = cur.thinkingText.ifBlank { cur.lastThinkingText },
            )
        }
    }

    private fun saveMissionCheckpoint(companyId: String, ckpt: MissionCheckpoint) {
        val mandatesJson = JSONObject()
        ckpt.botMandates.forEach { (k, v) -> mandatesJson.put(k, v) }
        val obj = JSONObject()
            .put("mission_id", ckpt.missionId)
            .put("title", ckpt.title)
            .put("goal", ckpt.goal)
            .put("scoped_spec", ckpt.scopedSpec.take(12_000))
            .put("completed_bot_ids", JSONArray(ckpt.completedBotIds.toList()))
            .put("interrupted_bot_id", ckpt.interruptedBotId ?: "")
            .put("bot_mandates", mandatesJson)
        prefs.edit().putString("mission_ckpt_$companyId", obj.toString()).apply()
    }

    private fun loadMissionCheckpoint(companyId: String): MissionCheckpoint? {
        val raw = prefs.getString("mission_ckpt_$companyId", null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val compArr = obj.optJSONArray("completed_bot_ids") ?: JSONArray()
            val completed = (0 until compArr.length()).map { compArr.optString(it) }.filter { it.isNotBlank() }.toMutableSet()
            val mObj = obj.optJSONObject("bot_mandates") ?: JSONObject()
            val mandates = buildMap<String, String> {
                val keys = mObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, mObj.optString(k))
                }
            }
            MissionCheckpoint(
                missionId = obj.optString("mission_id"),
                title = obj.optString("title"),
                goal = obj.optString("goal"),
                scopedSpec = obj.optString("scoped_spec"),
                completedBotIds = completed,
                interruptedBotId = obj.optString("interrupted_bot_id").ifBlank { null },
                botMandates = mandates,
            )
        }.getOrNull()
    }

    private fun clearMissionCheckpoint(companyId: String) {
        prefs.edit().remove("mission_ckpt_$companyId").apply()
    }

    private fun appendTeamFeed(companyId: String, actorName: String, summaryLine: String) {
        val cleanLine = "- [$actorName] ${summaryLine.replace("\n", " ").take(260)}"
        val existing = runCatching { sandbox.readTextFile(companyId, "workspace/team_feed.md") }.getOrDefault("# Shared Team Feed\n")
        val updated = "$existing\n$cleanLine".takeLast(14_000)
        runCatching { sandbox.writeTextFile(companyId, "workspace/team_feed.md", updated) }
        db.rememberNote("mem_${UUID.randomUUID().toString().take(8)}", companyId, actorName, "team_feed", cleanLine)
    }

    private fun buildSharedTeamContext(companyId: String): String {
        val arts = db.listArtifacts(companyId).take(12)
        val artLines = if (arts.isEmpty()) "none yet" else arts.joinToString("\n") { a ->
            "- ${a.filename} (${a.relPath}, ${a.sizeBytes}B) by ${a.botName} [QA=${a.qaStatus}]: ${a.summary}"
        }
        val feed = runCatching { sandbox.readTextFile(companyId, "workspace/team_feed.md") }.getOrDefault("")
            .lines().takeLast(14).joinToString("\n")
        val journal = runCatching { sandbox.readTextFile(companyId, "workspace/company_journal.md") }.getOrDefault("")
            .lines().takeLast(16).joinToString("\n")
        return "SHARED ARTIFACTS CREATED SO FAR (ALL BOTS SEE THIS):\n$artLines\n\nSHARED TEAM ACTIVITY FEED:\n${feed.ifBlank { "none yet" }}\n\nCOMPANY LONG-TERM MEMORY JOURNAL:\n${journal.ifBlank { "none yet" }}"
    }

    private fun syncCompanyNotionLedger(
        comp: CompanyItem,
        sectionTitle: String,
        entryMarkdown: String,
    ): String? {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())
        val formattedEntry = "\n## $stamp · $sectionTitle\n$entryMarkdown\n"
        val prevJournal = runCatching { sandbox.readTextFile(comp.id, "workspace/company_journal.md") }.getOrDefault("")
        val fullJournal = (prevJournal + formattedEntry).trimStart()
        sandbox.writeTextFile(comp.id, "workspace/company_journal.md", fullJournal)

        val exec = pluginExecutor ?: return null
        val notionTok = pluginAuth?.getAccessToken("notion").orEmpty()
        if (notionTok.isBlank()) return null

        val pagePrefKey = "notion_page_${comp.id}"
        val syncedLenKey = "notion_synced_len_${comp.id}"
        val existingPageId = prefs.getString(pagePrefKey, "").orEmpty()
        val syncedLen = prefs.getInt(syncedLenKey, 0).coerceAtMost(prevJournal.length)
        val unsyncedContent = if (existingPageId.isBlank() && fullJournal.length > syncedLen) {
            fullJournal.takeLast(3500)
        } else {
            formattedEntry
        }

        val res = exec.ensureAndAppendCompanyNotionPage(
            companyName = comp.name,
            objective = comp.description,
            existingPageId = existingPageId,
            sectionTitle = sectionTitle,
            entryMarkdown = unsyncedContent,
        ) ?: return null

        prefs.edit()
            .putString(pagePrefKey, res.first)
            .putInt(syncedLenKey, fullJournal.length)
            .apply()
        return res.second
    }

    private fun harvestNativeCliArtifacts(companyId: String, botName: String, seenOutNames: MutableSet<String>): List<ArtifactItem> {
        val newFiles = sandbox.listOutputFiles(companyId).filter { it.name !in seenOutNames }
        val harvested = mutableListOf<ArtifactItem>()
        for (f in newFiles) {
            seenOutNames.add(f.name)
            val art = registerArtifact(companyId, botName, f.nameWithoutExtension, f, "Generated in sandbox via CLI/IO")
            harvested.add(art)
            appendTeamFeed(companyId, botName, "Created artifact ${art.filename} (${art.relPath}, ${art.sizeBytes}B)")
        }
        return harvested
    }

    private suspend fun executeToolOrBatch(
        comp: CompanyItem,
        bot: BotItem,
        parsed: JSONObject,
        onStatusUpdate: suspend (String) -> Unit,
    ): Pair<String, List<ArtifactItem>> {
        val created = mutableListOf<ArtifactItem>()
        val obsList = mutableListOf<String>()
        val batch = parsed.optJSONArray("calls")
        if (batch != null && batch.length() > 0) {
            for (i in 0 until minOf(batch.length(), 6)) {
                val c = batch.optJSONObject(i) ?: continue
                val t = c.optString("tool")
                if (t.isBlank()) continue
                val a = c.optJSONObject("args") ?: JSONObject()
                onStatusUpdate("${bot.name} running $t...")
                val (obs, art) = executeAnyTool(comp, bot, t, a, onStatusUpdate)
                art?.let { created.add(it) }
                obsList.add("[$t] -> $obs")
            }
        } else {
            val t = parsed.optString("tool").ifBlank { if (parsed.has("chapters") || parsed.has("sections")) "create_pdf" else "" }
            if (t.isNotBlank()) {
                val a = parsed.optJSONObject("args") ?: JSONObject()
                onStatusUpdate("${bot.name} running $t...")
                val (obs, art) = executeAnyTool(comp, bot, t, a, onStatusUpdate)
                art?.let { created.add(it) }
                obsList.add("[$t] -> $obs")
            }
        }
        return obsList.joinToString("\n\n") to created
    }

    private suspend fun <T> withWallTimeoutOrNull(timeoutMs: Long, block: suspend () -> T): T? =
        withContext(Dispatchers.IO) { withTimeoutOrNull(timeoutMs) { block() } }

    private suspend fun ceoInterrogateBlockedBot(
        comp: CompanyItem,
        ceoBot: BotItem,
        stalledBot: BotItem,
        reason: String,
        onStatusUpdate: suspend (String) -> Unit,
    ): String {
        val live = _botTelemetry.value[stalledBot.id]
        val lastTool = live?.currentTool?.ifBlank { "none" } ?: "none"
        val lastPlugin = live?.currentPlugin?.ifBlank { "Sandbox" } ?: "Sandbox"
        val lastUrl = live?.currentUrl?.ifBlank { "on-device" } ?: "on-device"
        val lastThought = live?.currentReasoning?.take(220).orEmpty()

        onStatusUpdate("CEO interrogating ${stalledBot.name} ($reason)...")
        recordBotActivity(ceoBot, "working", task = "Interrogating ${stalledBot.name}", tool = "ceo_interrogate", plugin = "CEO Oversight")

        val diagSummary = if (hasActiveEngine() && !killSwitch) {
            val aiDiag = withWallTimeoutOrNull(15_000L) {
                runAiCompletion(
                    systemPrompt = "You are CEO of '${comp.name}'. Briefly interrogate why specialist '${stalledBot.name}' (${stalledBot.role}) blocked/stopped and give a crisp 1-2 sentence recovery plan in the team's language.",
                    userPrompt = "Trigger: $reason\nBot last tool: $lastTool ($lastPlugin | $lastUrl)\nLast reasoning: $lastThought\nShared context:\n${buildSharedTeamContext(comp.id).take(1200)}",
                    sandboxDir = sandbox.companyRoot(comp.id),
                    modelSpec = ceoBot.model,
                    maxTokens = 260,
                    onStatus = onStatusUpdate,
                )
            }
            if (aiDiag != null && aiDiag.ok && aiDiag.content.isNotBlank()) aiDiag.content.trim().take(280)
            else "$reason (dernier outil : $lastTool sur $lastPlugin). Reprise vers le bot suivant."
        } else {
            "$reason (dernier outil : $lastTool sur $lastPlugin). Reprise vers le bot suivant."
        }

        val msgText = "🕵️ [CEO ⇄ ${stalledBot.name}] $reason — Diagnostic CEO : $diagSummary"
        recordBotActivity(stalledBot, "idle", task = "Skipped after CEO interrogation", reasoning = diagSummary)
        db.updateBotStatus(stalledBot.id, "idle", "CEO skipped/unblocked")
        appendTeamFeed(comp.id, "CEO", "Interrogated ${stalledBot.name}: $diagSummary")
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                comp.id,
                "bot",
                botId = ceoBot.id,
                botName = "CEO",
                content = msgText,
            )
        )
        return diagSummary
    }

    private fun beginReasoning(actorName: String, modelLabel: String = "", statusLine: String = "Thinking...") {
        _liveReasoning.value = _liveReasoning.value.copy(
            active = true,
            actorName = actorName,
            modelLabel = modelLabel,
            statusLine = statusLine,
            thinkingText = "",
            startedAtMs = System.currentTimeMillis(),
        )
    }

    private fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return
        val cur = _liveReasoning.value
        val next = (cur.thinkingText + delta).takeLast(3500)
        _liveReasoning.value = cur.copy(thinkingText = next)
    }

    private fun updateReasoningStatus(line: String) {
        val cur = _liveReasoning.value
        if (cur.active) {
            _liveReasoning.value = cur.copy(statusLine = line)
        }
    }

    private fun finishReasoning(summary: String = "") {
        val cur = _liveReasoning.value
        val secs = ((System.currentTimeMillis() - cur.startedAtMs).coerceAtLeast(0L) / 1000L)
        _liveReasoning.value = cur.copy(
            active = false,
            lastCompletedSummary = summary.ifBlank { "${cur.actorName} reasoned (${secs}s)" },
            lastThinkingText = cur.thinkingText.ifBlank { cur.lastThinkingText },
        )
    }

    private fun recordBotActivity(
        bot: BotItem,
        status: String,
        task: String = "",
        plugin: String = "",
        tool: String = "",
        url: String = "",
        reasoning: String = "",
        addTrace: BotActionTrace? = null,
    ) {
        val prev = _botTelemetry.value[bot.id]
        val traces = if (addTrace != null) {
            ((prev?.traces ?: emptyList()) + addTrace).takeLast(25)
        } else {
            prev?.traces ?: emptyList()
        }
        val next = BotLiveActivity(
            botId = bot.id,
            botName = bot.name,
            role = bot.role,
            avatarShape = bot.avatarShape,
            avatarColor = bot.avatarColor,
            status = status,
            currentTask = task.ifBlank { prev?.currentTask.orEmpty() },
            currentPlugin = plugin.ifBlank { prev?.currentPlugin.orEmpty() },
            currentTool = tool.ifBlank { prev?.currentTool.orEmpty() },
            currentUrl = url.ifBlank { prev?.currentUrl.orEmpty() },
            currentReasoning = reasoning.ifBlank { prev?.currentReasoning.orEmpty() }.takeLast(1200),
            updatedAtMs = System.currentTimeMillis(),
            traces = traces,
        )
        _botTelemetry.value = _botTelemetry.value + (bot.id to next)
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
        if (runCancelled || killSwitch) {
            return LLMCallResult(ok = false, provider = "stopped", error = "Stopped by Founder")
        }
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
                onStatus = { st ->
                    updateReasoningStatus(st)
                    onStatus(st)
                },
                onThinkingDelta = { delta -> appendReasoning(delta) },
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
            if (runCancelled || !llm.hasAnyKey()) {
                return LLMCallResult(ok = false, provider = "antigravity", error = output.ifBlank { "Antigravity CLI failed" })
            }
        }
        val llmRes = llm.complete(
            messages = listOf("system" to systemPrompt, "user" to userPrompt),
            modelSpec = modelSpec,
            jsonMode = jsonMode,
            maxTokens = maxTokens,
        )
        if (llmRes.ok && llmRes.content.isNotBlank()) {
            appendReasoning("\n[${llmRes.provider}/${llmRes.model}] ${llmRes.content.take(400)}\n")
        }
        return llmRes
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
        "create_xlsx",
        "create_pptx",
        "create_docx",
        "create_png_graphic",
        "create_svg_graphic",
        "create_web_app",
        "create_notion_pack",
        "create_csv_dataset",
        "create_zip",
        "web_fetch",
        "company_memory",
        "artifact_publish",
        "http_api_call",
    )

    suspend fun proposeCompany(description: String): CompanyProposalData {
        resetCancellation()
        beginReasoning("CEO", agy?.selectedModel ?: "Architect", "Designing company team & plugins...")
        val prompt = """
            You are the Founder's AI CEO inside an autonomous multi-sandbox Android OS.
            Nothing is hardcoded by industry. The human described a new company to launch:
            "$description"

            Design the exact team of specialist bots (3 to 8 bots, NOT counting the CEO which is added automatically), splitting distinct functions cleanly (e.g. Market & Niche Analyst, Data/Content Researcher, Multi-Format Product Producer for spreadsheets/slides/docs/Notion/books/apps, Visuals & Mockups Designer, Independent Quality Auditor, Marketing/Video Studio, Social & 24/7 Customer Care/Sales Operator when relevant to the objective).
            ${DigitalProductSkills.marketCapabilitiesPrompt()}
            ${pluginExecutor?.activeToolsSummaryForPrompt().orEmpty()}
            Rules:
            - Output strictly ONE JSON object.
            - MUST include at least ONE independent Quality/Verification bot (`"is_qa": true`) that audits deliverables before human approval.
            - MUST include a Memory bot (`company_memory`) to keep all bots aligned.
            - As CEO, YOU MUST author a distinct, concrete `role` AND a detailed multi-sentence `system_prompt` for EACH specialist bot strictly tailored to (a) this exact company objective (`$description`), (b) that bot's individual specialty/KPIs, (c) its assigned `allowed_tools` / plugins, and (d) autonomous execution + `[Bot → CEO]` reporting.
            - Assign both sandbox atomic tools (${JSONArray(atomicTools)}) and relevant connected/available plugin tool names in `allowed_tools` per specialist bot so every bot understands its exact job and reaches the company objective.
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
        val parsedName = parsed?.optString("name").orEmpty().trim()
        val isTemplateEcho = parsedName.equals("Short crisp company name", ignoreCase = true) ||
            parsed?.optString("mission_statement").orEmpty().trim().equals("1-sentence mission", ignoreCase = true)
        if (parsed != null && !isTemplateEcho && parsed.optJSONArray("bots")?.length() ?: 0 >= 2) {
            finishReasoning("Designed ${parsed.optString("name", "company")} team")
            return normalizeProposal(parsed, description)
        }
        finishReasoning(if (resp.ok) "Proposal structured" else "Error: ${resp.error.take(60)}")
        val errDetail = when {
            !hasActiveEngine() -> "Aucun moteur IA connecté (installez Antigravity CLI et connectez Google dans ⚙️ Settings)."
            !resp.ok -> "Erreur moteur IA (${resp.provider}): ${resp.error.take(180)}"
            else -> "Réponse IA non structurée: ${resp.content.take(140)}"
        }
        return synthesizeAdaptiveFallback(description, errDetail)
    }

    private fun normalizeProposal(raw: JSONObject, description: String, errorNote: String = ""): CompanyProposalData {
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
        for (i in 0 until minOf(rawBots.length(), 9)) {
            val b = rawBots.optJSONObject(i) ?: continue
            val bName = b.optString("name").trim()
            if (bName.isBlank() || bName.equals("ceo", ignoreCase = true)) continue
            val (shape, color) = avatarPalette[i % avatarPalette.size]
            val tArr = b.optJSONArray("allowed_tools") ?: JSONArray()
            val pluginToolNames = PluginRegistry.ALL.flatMap { p -> p.tools.map { it.name } }.toSet()
            val tools = (0 until tArr.length())
                .map { tArr.optString(it) }
                .filter { it in atomicTools || it in pluginToolNames }
                .ifEmpty { listOf("sandbox_files", "create_pdf", "company_memory", "artifact_publish", "browser_search") }
            val isQa = b.optBoolean("is_qa", false)
            if (isQa) hasQa = true
            val botRole = b.optString("role", bName).take(60)
            val botPrompt = enrichBotSystemPrompt(
                botName = bName.take(32),
                role = botRole,
                companyName = name,
                companyObjective = mission.ifBlank { description },
                rawPrompt = b.optString("system_prompt"),
                allowedTools = tools,
                isQa = isQa,
            )
            bots.add(
                BotItem(
                    id = "b_${i + 1}",
                    companyId = "",
                    name = bName.take(32),
                    role = botRole,
                    tagline = b.optString("tagline", "Executes assigned tasks").take(60),
                    systemPrompt = botPrompt,
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
                    systemPrompt = enrichBotSystemPrompt(
                        botName = "Quality Auditor",
                        role = "Independent QA & Verification",
                        companyName = name,
                        companyObjective = mission.ifBlank { description },
                        rawPrompt = "Independently verify quality, accuracy, and completeness of every deliverable before human approval.",
                        allowedTools = listOf("sandbox_files", "company_memory"),
                        isQa = true,
                    ),
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
            errorNote = errorNote,
        )
    }

    private fun enrichBotSystemPrompt(
        botName: String,
        role: String,
        companyName: String,
        companyObjective: String,
        rawPrompt: String,
        allowedTools: List<String>,
        isQa: Boolean,
    ): String {
        val cleaned = rawPrompt.trim()
        val hasObjective = cleaned.contains(companyObjective.take(24), ignoreCase = true) ||
            cleaned.contains(companyName, ignoreCase = true)
        val hasRole = cleaned.contains(role, ignoreCase = true)
        val toolsStr = allowedTools.joinToString(", ").ifBlank { "sandbox_files" }
        if (cleaned.length >= 110 && hasObjective && hasRole) {
            return cleaned
        }
        val coreMandate = cleaned.ifBlank {
            if (isQa) "Independently audit every deliverable against strict quality and factual accuracy before release approval."
            else "Autonomously execute $role deliverables cleanly inside the company sandbox."
        }
        return buildString {
            append("You are $botName ($role) at '$companyName'.\n")
            append("Company objective: $companyObjective\n")
            append("Your individual mandate assigned by CEO: $coreMandate\n")
            append("Assigned tools & plugins: $toolsStr\n")
            append("Always act autonomously using real tools/plugins, never hallucinate outputs, and report results clearly to CEO.")
        }
    }

    private fun synthesizeAdaptiveFallback(description: String, errorNote: String = ""): CompanyProposalData {
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
                            .put("role", "Multi-Format Digital Product Producer")
                            .put("tagline", "Builds sellable digital assets")
                            .put("system_prompt", "Produce complete sellable digital products (Excel/Sheets .xlsx trackers, PowerPoint .pptx decks, Word .docx templates, Notion packs, interactive HTML apps, high-res PNG covers, illustrated PDFs & ZIP bundles) for '$clean' inside output/.")
                            .put("allowed_tools", JSONArray(listOf("sandbox_files", "create_pdf", "create_xlsx", "create_pptx", "create_docx", "create_png_graphic", "create_svg_graphic", "create_web_app", "create_notion_pack", "create_csv_dataset", "create_zip", "artifact_publish", "company_memory")))
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
        return normalizeProposal(raw, description, errorNote)
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
        val mandatesSummary = persistedBots.filterNot { it.isCeo }.joinToString("\n") { b ->
            "• ${b.name} (${b.role}): ${b.systemPrompt.take(140)}"
        }
        if (mandatesSummary.isNotBlank()) {
            db.rememberNote("mem_${UUID.randomUUID().toString().take(8)}", cid, "CEO", "ceo_mandates", mandatesSummary)
        }

        db.insertMessage(ChatMessageItem("msg_${UUID.randomUUID().toString().take(10)}", cid, "human", content = description))
        val ceoOpening = buildString {
            if (proposal.errorNote.isNotBlank()) {
                append("⚠️ ").append(proposal.errorNote).append("\n\n")
            }
            append(proposal.strategySummary.ifBlank {
                "Entreprise '${proposal.name}' prête (${persistedBots.size} bots). Dis-moi quel objectif tu veux analyser ou construire."
            })
            if (mandatesSummary.isNotBlank()) {
                append("\n\n👔 Rôles et prompts attribués par le CEO :\n").append(mandatesSummary)
            }
        }
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                cid,
                "bot",
                botName = "CEO",
                content = ceoOpening,
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

        val initSummary = buildString {
            append("- Objectif : ${proposal.missionStatement.ifBlank { description }}\n")
            append("- Équipe : ${persistedBots.joinToString(", ") { "${it.name} (${it.role})" }}\n")
            if (mandatesSummary.isNotBlank()) append("\n$mandatesSummary")
        }
        val notionUrl = syncCompanyNotionLedger(comp, "Création de l'entreprise & Mandats", initSummary)
        if (!notionUrl.isNullOrBlank()) {
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    cid,
                    "bot",
                    botName = "CEO",
                    content = "📒 Page Notion dédiée créée pour '${proposal.name}' (suivi & mémoire long terme) : $notionUrl",
                )
            )
        }

        comp.copy(bots = persistedBots)
    }

    suspend fun handleFounderMessage(
        companyId: String,
        text: String,
        attachmentsSummary: String = "",
        displayMessage: String = text,
        targetBotId: String? = null,
        insertHumanMessage: Boolean = true,
        onStatusUpdate: suspend (String) -> Unit = {},
    ) {
        resetCancellation()
        val comp = db.getCompany(companyId) ?: return
        if (insertHumanMessage) {
            db.insertMessage(ChatMessageItem("msg_${UUID.randomUUID().toString().take(10)}", companyId, "human", content = displayMessage))
        }

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
        val ceoBot = bots.firstOrNull { it.isCeo } ?: bots.firstOrNull()
        val targetBot = targetBotId?.let { id -> bots.firstOrNull { it.id == id } }

        if (targetBot != null && !targetBot.isCeo) {
            beginReasoning(targetBot.name, targetBot.model, "${targetBot.name} reasoning...")
            onStatusUpdate("${targetBot.name} is reasoning...")
            recordBotActivity(targetBot, "working", task = text.take(80), reasoning = "Analyzing founder request...")
            db.updateBotStatus(targetBot.id, "working", "Thinking...")
            val reply = runDirectBotChat(comp, targetBot, enrichedPrompt, onStatusUpdate)
            val stopped = runCancelled || killSwitch
            recordBotActivity(
                targetBot,
                status = if (stopped) "stopped" else "idle",
                task = if (stopped) "Stopped by Founder" else reply.take(80),
                reasoning = _liveReasoning.value.thinkingText,
            )
            db.updateBotStatus(targetBot.id, "idle", if (stopped) "Stopped" else reply.take(50))
            finishReasoning(if (stopped) "Stopped" else "${targetBot.name} finished")
            if (!stopped || reply.isNotBlank()) {
                if (stopped) {
                    db.insertMessage(
                        ChatMessageItem(
                            "msg_${UUID.randomUUID().toString().take(10)}",
                            companyId,
                            "bot",
                            botId = targetBot.id,
                            botName = targetBot.name,
                            content = "⏹️ Stopped (${targetBot.name}).",
                        )
                    )
                } else {
                    insertBotReplyWithChoices(companyId, targetBot.id, targetBot.name, reply, explicitChoices = null)
                }
            }
            return
        }

        // Check if Founder is resuming a stopped/blocked mission ("continue", "reprends", "suivant", ...)
        val lowerTrim = text.trim().lowercase()
        val isResumeIntent = targetBotId == null && listOf(
            "continue", "continuer", "reprend", "reprends", "resume", "suivant", "passe au suivant", "next bot", "go on"
        ).any { lowerTrim == it || lowerTrim.startsWith("$it ") }
        val savedCkpt = loadMissionCheckpoint(companyId)
        if (isResumeIntent && savedCkpt != null) {
            beginReasoning("CEO", agy?.selectedModel ?: "auto", "Resuming mission & interrogating blocked step...")
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    companyId,
                    "bot",
                    botId = ceoBot?.id,
                    botName = "CEO",
                    content = "▶️ Reprise de la mission « ${savedCkpt.title} » — vérification du blocage puis passage au bot suivant.",
                )
            )
            runFullMission(
                comp = comp,
                bots = bots,
                title = savedCkpt.title,
                goal = savedCkpt.goal,
                botMandates = savedCkpt.botMandates,
                resumeCheckpoint = savedCkpt,
                onStatusUpdate = onStatusUpdate,
            )
            finishReasoning("Mission resumed")
            return
        }

        // User is addressing CEO directly -> always reason via Antigravity CLI / active AI engine
        runCeoReasoningTurn(comp, ceoBot, bots, enrichedPrompt, onStatusUpdate)
    }

    suspend fun editAndResendMessage(
        companyId: String,
        messageId: String,
        newText: String,
        targetBotId: String? = null,
        onStatusUpdate: suspend (String) -> Unit = {},
    ) {
        val cleaned = newText.trim()
        if (cleaned.isBlank()) return
        db.updateMessageContent(messageId, cleaned)
        db.deleteMessagesAfter(companyId, messageId)
        handleFounderMessage(
            companyId = companyId,
            text = cleaned,
            displayMessage = cleaned,
            targetBotId = targetBotId,
            insertHumanMessage = false,
            onStatusUpdate = onStatusUpdate,
        )
    }

    suspend fun regenerateResponse(
        companyId: String,
        botMessageId: String,
        onStatusUpdate: suspend (String) -> Unit = {},
    ): Boolean {
        val msgs = db.listMessages(companyId)
        val idx = msgs.indexOfFirst { it.id == botMessageId }
        if (idx < 0) return false
        val targetMsg = msgs[idx]
        val prevHuman = msgs.subList(0, idx).lastOrNull { it.senderType == "human" } ?: return false
        val targetBotId = targetMsg.botId?.let { id ->
            db.listBots(companyId).firstOrNull { it.id == id && !it.isCeo }?.id
        }
        db.deleteMessagesAfter(companyId, prevHuman.id)
        handleFounderMessage(
            companyId = companyId,
            text = prevHuman.content,
            displayMessage = prevHuman.content,
            targetBotId = targetBotId,
            insertHumanMessage = false,
            onStatusUpdate = onStatusUpdate,
        )
        return true
    }

    private fun insertBotReplyWithChoices(
        companyId: String,
        botId: String?,
        botName: String,
        rawReply: String,
        explicitChoices: List<ChoiceOption>? = null,
    ) {
        var finalText = llm.sanitizeAssistantDisplayText(rawReply).ifBlank { rawReply.trim() }
        var options = explicitChoices

        if (options.isNullOrEmpty()) {
            val maybeJson = llm.extractJsonObject(finalText)
            val fromJson = maybeJson?.let { ChoiceExtractor.parseStructuredChoices(it) }
            if (fromJson != null) {
                finalText = fromJson.first
                options = fromJson.second
            } else {
                val fromMd = ChoiceExtractor.detectChoicesFromMarkdown(finalText)
                if (fromMd != null) {
                    finalText = fromMd.first
                    options = fromMd.second
                }
            }
        }

        if (!options.isNullOrEmpty()) {
            val choiceData = ChoicePromptData(options = options)
            db.insertMessage(
                ChatMessageItem(
                    id = "msg_${UUID.randomUUID().toString().take(10)}",
                    companyId = companyId,
                    senderType = "bot",
                    botId = botId,
                    botName = botName,
                    content = finalText,
                    cardType = "choices",
                    choices = choiceData,
                ),
                rawPayloadJson = ChoiceExtractor.toPayloadJson(choiceData),
            )
        } else {
            db.insertMessage(
                ChatMessageItem(
                    id = "msg_${UUID.randomUUID().toString().take(10)}",
                    companyId = companyId,
                    senderType = "bot",
                    botId = botId,
                    botName = botName,
                    content = finalText,
                )
            )
        }
    }

    private suspend fun runCeoReasoningTurn(
        comp: CompanyItem,
        ceoBot: BotItem?,
        bots: List<BotItem>,
        userPrompt: String,
        onStatusUpdate: suspend (String) -> Unit,
    ) {
        if (!hasActiveEngine()) {
            val diag = when {
                agy == null || !agy.installer.isInstalled() ->
                    "Antigravity CLI (`agy`) n'est pas encore installé sur ce téléphone. Ouvre ⚙️ Settings → Antigravity CLI → « Install CLI », puis connecte ton compte Google."
                agy.accounts.readLiveToken() == null ->
                    "Antigravity CLI est installé mais aucun compte Google n'est connecté. Ouvre ⚙️ Settings → « Sign in with Google » pour que je puisse raisonner avec tes modèles."
                !agy.preferAgyEngine ->
                    "L'exécution via Antigravity CLI est désactivée et aucune clé API externe n'est configurée. Active « Run CEO via Antigravity CLI » ou ajoute une clé dans ⚙️ Settings."
                else ->
                    "Aucun moteur IA actif. Configure Antigravity CLI ou une clé API dans ⚙️ Settings."
            }
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    comp.id,
                    "bot",
                    botId = ceoBot?.id,
                    botName = "CEO",
                    content = "⚠️ $diag",
                )
            )
            return
        }

        val activeModelLabel = if (canUseAgyEngine()) agy!!.selectedModel else (ceoBot?.model ?: "LLM")
        beginReasoning("CEO", activeModelLabel, "Analyzing request & company tools...")
        ceoBot?.let {
            recordBotActivity(it, "working", task = userPrompt.take(80), reasoning = "Thinking about founder prompt...")
            db.updateBotStatus(it.id, "working", "Reasoning...")
        }
        onStatusUpdate("CEO is thinking with Antigravity CLI...")

        val mem = db.recallNotes(comp.id, 6).joinToString("\n")
        val files = sandbox.listFiles(comp.id).joinToString(", ") { "${it.first} (${it.second}B)" }
        val teamDesc = bots.joinToString(", ") { "${it.name} (${it.role})" }
        val recentChat = db.listMessages(comp.id)
            .takeLast(8)
            .joinToString("\n") { m ->
                val who = if (m.senderType == "human") "Founder" else m.botName.ifBlank { "CEO" }
                "$who: ${m.content.take(400)}"
            }

        val systemPrompt = """
            You are the Founder's AI CEO of '${comp.name}' running directly inside an on-device sandbox with Antigravity CLI (`agy`) and connected Plugins.
            Company objective: ${comp.description}
            Your team: $teamDesc
            Sandbox files: ${files.ifBlank { "none" }}
            ${pluginExecutor?.activeToolsSummaryForPrompt().orEmpty()}
            ${buildSharedTeamContext(comp.id)}
            Company memory:
            ${mem.ifBlank { "none" }}

            Instructions:
            - Respond in the exact same language as the Founder (e.g. French if the founder writes in French).
            - BREVITY & CONCISENESS (CRITICAL): For normal conversational messages, status checks, confirmations, or advice, keep your reply BRIEF, DIRECT, and CONCISE (2 to 4 short sentences, or at most 4 tight bullets). Avoid fluff, long preambles, or repeating what the Founder already knows. Only write long detailed prose if explicitly requested or inside generated files/deliverables.
            - INTERACTIVE CHOICES (CRITICAL): Whenever you present options, strategic choices, or ask the Founder to choose between 2 to 5 directions/actions, keep your prompt to 1-2 short sentences and ALWAYS output ONE JSON object so the app renders one-tap clickable ChatGPT-style cards:
              {"action":"choices","reply":"Short crisp question (1-2 sentences)","choices":[{"label":"Short option title","description":"Brief 1-line detail (optional)","value":"Exact concise choice sent when tapped"}]}
            - Think step-by-step and give a genuinely reasoned, concrete, helpful CEO answer aligned with the company objective. Never output canned filler.
            - As CEO, you can assign or update any specialist bot's individual role and tailored system prompt aligned with the company objective by calling:
              {"action":"tool","tool":"assign_bot_prompt","args":{"bot_name":"Builder","role":"...","system_prompt":"..."}}
            - You CAN call any connected Plugin or Sandbox tool directly before answering by outputting ONE JSON object:
              {"action":"tool","tool":"<tool_name>","args":{...}}
              Supported sandbox tools: list_files, read_file, write_file, create_pdf, create_xlsx, create_pptx, create_docx, create_png_graphic, create_svg_graphic, create_web_app, create_notion_pack, create_csv_dataset, create_zip, product_design_prototype, assign_bot_prompt.
              ${DigitalProductSkills.marketCapabilitiesPrompt()}
              Or ANY connected plugin tool above (browser_search, browser_read_url, notion_search, github_list_repos, slack_list_channels, gmail_search, gdrive_search, figma_get_file, canva_list_designs, scite_search, tinyfish_extract, etc.).
            - ONLY if the Founder wants a full multi-bot pipeline producing deliverables with QA + Approval, output:
              {"action":"run_mission","title":"Short mission title","goal":"Detailed mission goal","ceo_reply":"Brief 1-2 sentence CEO plan","bot_mandates":{"Strategist":"Tailored mission directive aligned with role & objective","Builder":"Tailored mission directive"}}
            - When ready to reply directly (without choices), respond with brief, concise prose/markdown (or {"action":"final","reply":"..."}).
        """.trimIndent()

        val history = mutableListOf<String>()
        if (recentChat.isNotBlank()) history.add("Recent conversation:\n$recentChat")
        history.add("Latest message to CEO:\n$userPrompt")

        var finalReply = ""
        var finalChoices: List<ChoiceOption>? = null
        for (step in 1..6) {
            if (runCancelled || killSwitch) break
            updateReasoningStatus(if (step == 1) "CEO reasoning via $activeModelLabel..." else "CEO step $step/6...")
            onStatusUpdate(if (step == 1) "CEO reasoning via Antigravity CLI..." else "CEO step $step/6...")
            val res = runAiCompletion(
                systemPrompt = systemPrompt,
                userPrompt = history.joinToString("\n\n"),
                sandboxDir = sandbox.companyRoot(comp.id),
                modelSpec = ceoBot?.model ?: "auto",
                maxTokens = 2200,
                onStatus = onStatusUpdate,
            )
            if (runCancelled || killSwitch) break
            if (!res.ok) {
                ceoBot?.let {
                    recordBotActivity(it, "error", task = res.error.take(80))
                    db.updateBotStatus(it.id, "idle", "Error")
                }
                finishReasoning("Error (${res.provider})")
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        comp.id,
                        "bot",
                        botId = ceoBot?.id,
                        botName = "CEO",
                        content = "⚠️ Antigravity CLI / LLM n'a pas pu répondre (${res.provider}) : ${res.error}",
                    )
                )
                return
            }
            db.addTokensUsed(comp.id, res.tokensUsed)
            val obj = llm.extractActionOrToolCall(res.content)
            val act = obj?.optString("action")?.lowercase().orEmpty()
            when {
                act == "run_mission" -> {
                    val ceoReply = obj?.optString("ceo_reply").orEmpty().trim()
                    if (ceoReply.isNotBlank()) {
                        appendReasoning("\n[CEO Delegation Plan] $ceoReply\n")
                        db.insertMessage(
                            ChatMessageItem(
                                "msg_${UUID.randomUUID().toString().take(10)}",
                                comp.id,
                                "bot",
                                botId = ceoBot?.id,
                                botName = "CEO",
                                content = ceoReply,
                            )
                        )
                    }
                    val mTitle = obj?.optString("title").orEmpty().ifBlank { userPrompt.take(58) }
                    val mGoal = obj?.optString("goal").orEmpty().ifBlank { userPrompt }
                    val mandatesObj = obj?.optJSONObject("bot_mandates")
                    val mandatesMap = buildMap<String, String> {
                        if (mandatesObj != null) {
                            val keys = mandatesObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = mandatesObj.optString(k).trim()
                                if (v.isNotBlank()) put(k, v)
                            }
                        }
                    }
                    runFullMission(comp, bots, title = mTitle, goal = mGoal, botMandates = mandatesMap, onStatusUpdate = onStatusUpdate)
                    finishReasoning("Mission completed")
                    return
                }
                act == "tool" || act == "tools" || (obj != null && obj.has("calls")) -> {
                    val seenOut = sandbox.listOutputFiles(comp.id).map { it.name }.toMutableSet()
                    val (obs, createdArts) = executeToolOrBatch(comp, ceoBot ?: bots.first(), obj!!, onStatusUpdate)
                    val diskArts = harvestNativeCliArtifacts(comp.id, ceoBot?.name ?: "CEO", seenOut)
                    (createdArts + diskArts).forEach { emitArtifactCard(comp, ceoBot?.name ?: "CEO", it) }
                    history.add("Step $step CEO tool batch executed")
                    history.add("Observation:\n$obs")
                }
                act == "choices" && obj != null -> {
                    val parsedChoices = ChoiceExtractor.parseStructuredChoices(obj)
                    if (parsedChoices != null) {
                        finalReply = parsedChoices.first
                        finalChoices = parsedChoices.second
                    } else {
                        finalReply = obj.optString("reply").ifBlank { res.content }
                    }
                    break
                }
                act == "final" && obj != null -> {
                    val parsedChoices = ChoiceExtractor.parseStructuredChoices(obj)
                    if (parsedChoices != null) {
                        finalReply = parsedChoices.first
                        finalChoices = parsedChoices.second
                    } else {
                        finalReply = obj.optString("reply").ifBlank { obj.optString("summary") }.ifBlank { res.content }
                    }
                    break
                }
                else -> {
                    finalReply = res.content
                    break
                }
            }
        }

        if (runCancelled) {
            ceoBot?.let {
                recordBotActivity(it, "stopped", task = "Stopped by Founder")
                db.updateBotStatus(it.id, "idle", "Stopped")
            }
            finishReasoning("Stopped by Founder")
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    comp.id,
                    "bot",
                    botId = ceoBot?.id,
                    botName = "CEO",
                    content = "⏹️ Exécution interrompue à ta demande.",
                )
            )
            return
        }

        if (finalReply.isBlank()) {
            finalReply = history.lastOrNull().orEmpty().ifBlank { "Action exécutée dans la sandbox." }
        }
        ceoBot?.let {
            recordBotActivity(it, "idle", task = finalReply.take(80), reasoning = _liveReasoning.value.thinkingText)
            db.updateBotStatus(it.id, "idle", finalReply.take(48))
        }
        finishReasoning("CEO reasoned (${finalReply.length} chars)")
        insertBotReplyWithChoices(
            companyId = comp.id,
            botId = ceoBot?.id,
            botName = "CEO",
            rawReply = finalReply,
            explicitChoices = finalChoices,
        )
    }

    private suspend fun runDirectBotChat(
        comp: CompanyItem,
        bot: BotItem,
        userText: String,
        onStatusUpdate: suspend (String) -> Unit = {},
    ): String {
        if (!hasActiveEngine()) {
            return "⚠️ Antigravity CLI non connecté et aucune clé API configurée. Ouvre ⚙️ Settings pour installer `agy` / connecter Google."
        }
        val mem = db.recallNotes(comp.id, 6).joinToString("\n")
        val files = sandbox.listFiles(comp.id).joinToString(", ") { "${it.first} (${it.second}B)" }
        val recentChat = db.listMessages(comp.id)
            .takeLast(6)
            .joinToString("\n") { m ->
                val who = if (m.senderType == "human") "Founder" else m.botName.ifBlank { "CEO" }
                "$who: ${m.content.take(300)}"
            }

        val sysPrompt = """
            You are ${bot.name} (${bot.role}) at '${comp.name}'.
            Company objective: ${comp.description}
            Your instructions: ${bot.systemPrompt}
            Your assigned tools: ${bot.allowedTools.joinToString(", ")}
            Sandbox files: ${files.ifBlank { "none" }}
            ${pluginExecutor?.activeToolsSummaryForPrompt().orEmpty()}
            ${buildSharedTeamContext(comp.id)}
            Company memory:
            ${mem.ifBlank { "none" }}

            You are an autonomous agentic specialist. Always use connected plugins & Antigravity CLI/sandbox directly and record artifacts in `output/` so every bot stays aware of all progress:
            - Execute 1 tool: {"action":"tool","tool":"<tool_name>","args":{...}}
            - Execute multiple tools together: {"action":"tools","calls":[{"tool":"...","args":{...}},{"tool":"...","args":{...}}]}
            - If asking the Founder to choose between 2-5 options: {"action":"choices","reply":"Short question","choices":[{"label":"...","description":"...","value":"..."}]}
            - Otherwise reply directly with a brief, concise specialist answer (2-4 short sentences for normal replies; or {"action":"final","reply":"..."}). Match the Founder's language.
        """.trimIndent()

        val history = mutableListOf<String>()
        if (recentChat.isNotBlank()) history.add("Recent conversation:\n$recentChat")
        history.add("Founder request for ${bot.name}:\n$userText")
        val seenOutNames = sandbox.listOutputFiles(comp.id).map { it.name }.toMutableSet()

        for (step in 1..6) {
            if (killSwitch || runCancelled) break
            onStatusUpdate("${bot.name} reasoning (step $step)...")
            val res = runAiCompletion(
                systemPrompt = sysPrompt,
                userPrompt = history.joinToString("\n\n"),
                sandboxDir = sandbox.companyRoot(comp.id),
                modelSpec = bot.model,
                maxTokens = 1800,
                onStatus = onStatusUpdate,
            )
            if (!res.ok) return "⚠️ Erreur IA (${res.provider}): ${res.error}"
            db.addTokensUsed(comp.id, res.tokensUsed)

            // Pick up any native output/ files created directly on disk via Antigravity CLI I/O
            harvestNativeCliArtifacts(comp.id, bot.name, seenOutNames).forEach { emitArtifactCard(comp, bot.name, it) }

            val obj = llm.extractActionOrToolCall(res.content)
            val act = obj?.optString("action")?.lowercase().orEmpty()
            if ((act == "tool" || act == "tools" || (obj != null && obj.has("calls"))) && obj != null) {
                val (obs, createdArts) = executeToolOrBatch(comp, bot, obj, onStatusUpdate)
                createdArts.forEach { art ->
                    seenOutNames.add(art.filename)
                    emitArtifactCard(comp, bot.name, art)
                    appendTeamFeed(comp.id, bot.name, "Produced artifact ${art.filename} (${art.relPath})")
                }
                harvestNativeCliArtifacts(comp.id, bot.name, seenOutNames).forEach { emitArtifactCard(comp, bot.name, it) }
                history.add("Step $step executed tools")
                history.add("Observation:\n$obs")
            } else if (act == "final" && obj != null) {
                return obj.optString("reply").ifBlank { res.content }
            } else {
                return res.content
            }
        }
        return history.lastOrNull().orEmpty()
    }

    private suspend fun runFullMission(
        comp: CompanyItem,
        bots: List<BotItem>,
        title: String,
        goal: String,
        botMandates: Map<String, String> = emptyMap(),
        resumeCheckpoint: MissionCheckpoint? = null,
        onStatusUpdate: suspend (String) -> Unit,
    ) {
        // CEO tailors each specialist's role & prompt according to the company objective + mission + individual role
        val alignedBots = bots.map { b ->
            if (b.isCeo) b
            else {
                val ceoDirective = botMandates.entries.firstOrNull { it.key.equals(b.name, ignoreCase = true) }?.value.orEmpty()
                val updatedPrompt = if (ceoDirective.isNotBlank()) {
                    "You are ${b.name} (${b.role}) at '${comp.name}'.\nCompany objective: ${comp.description}\nCEO Mission Mandate: $ceoDirective\nCore role instructions: ${b.systemPrompt}"
                } else {
                    enrichBotSystemPrompt(
                        botName = b.name,
                        role = b.role,
                        companyName = comp.name,
                        companyObjective = comp.description,
                        rawPrompt = b.systemPrompt,
                        allowedTools = b.allowedTools,
                        isQa = b.isQa,
                    )
                }
                if (updatedPrompt != b.systemPrompt) {
                    db.updateBotRoleAndPrompt(b.id, b.role, updatedPrompt)
                    b.copy(systemPrompt = updatedPrompt)
                } else b
            }
        }

        val ceoBot = alignedBots.firstOrNull { it.isCeo } ?: alignedBots.first()
        val qaBot = alignedBots.firstOrNull { it.isQa } ?: alignedBots.last()
        val memoryBot = alignedBots.firstOrNull {
            !it.isCeo && !it.isQa && (it.name.equals("Memory", true) || it.allowedTools == listOf("company_memory"))
        }

        fun isResearchRole(b: BotItem): Boolean {
            val sig = "${b.name} ${b.role}".lowercase()
            return listOf("trend", "tendance", "research", "recherche", "strateg", "data", "analyst", "veil").any { it in sig }
        }
        fun isVisualRole(b: BotItem): Boolean {
            val sig = "${b.name} ${b.role}".lowercase()
            return listOf("graph", "visual", "chart", "design", "illustr").any { it in sig } ||
                b.allowedTools.contains("create_svg_graphic")
        }
        fun isMarketingOrSocialRole(b: BotItem): Boolean {
            val sig = "${b.name} ${b.role}".lowercase()
            return listOf("market", "video", "promo", "social", "insta", "tiktok", "sales", "vent", "support", "client", "comm", "publish", "growth").any { it in sig }
        }

        val candidates = alignedBots.filter { !it.isCeo && !it.isQa && it.id != memoryBot?.id }
        val researchBots = candidates.filter { isResearchRole(it) && !isVisualRole(it) && !isMarketingOrSocialRole(it) }
            .ifEmpty { candidates.filter { it.allowedTools.contains("web_fetch") || it.allowedTools.contains("browser_search") }.take(1) }
        val researchIds = researchBots.map { it.id }.toSet()

        val producerBot = candidates.firstOrNull {
            it.id !in researchIds && !isMarketingOrSocialRole(it) && !isVisualRole(it) &&
                (it.allowedTools.contains("create_pdf") || !isResearchRole(it))
        } ?: candidates.firstOrNull { it.id !in researchIds && !isMarketingOrSocialRole(it) }
            ?: candidates.firstOrNull { it.id !in researchIds }
            ?: ceoBot

        val visualDesignBots = candidates.filter { it.id !in researchIds && it.id != producerBot.id && isVisualRole(it) && !isMarketingOrSocialRole(it) }
        val stage1And2Ids = researchIds + visualDesignBots.map { it.id } + producerBot.id
        val postQaSpecialists = candidates.filter { it.id !in stage1And2Ids }

        val missionId = resumeCheckpoint?.missionId?.ifBlank { null } ?: "msn_${UUID.randomUUID().toString().take(8)}"
        if (resumeCheckpoint == null) {
            db.insertMission(missionId, comp.id, title, goal)
        }
        val completedIds = resumeCheckpoint?.completedBotIds ?: mutableSetOf()
        val researchDossier = StringBuilder(resumeCheckpoint?.scopedSpec?.ifBlank { goal } ?: goal)
        var interruptedTargetId = resumeCheckpoint?.interruptedBotId

        fun persistCkpt(interruptedId: String? = null) {
            saveMissionCheckpoint(
                comp.id,
                MissionCheckpoint(
                    missionId = missionId,
                    title = title,
                    goal = goal,
                    scopedSpec = researchDossier.toString(),
                    completedBotIds = completedIds.toMutableSet(),
                    interruptedBotId = interruptedId,
                    botMandates = botMandates,
                )
            )
        }

        currentMissionCompanyId = comp.id
        persistCkpt(null)

        try {
            db.updateBotStatus(ceoBot.id, "working", "Orchestrating mission...")
            if (resumeCheckpoint == null) {
                alignedBots.filterNot { it.isCeo }.forEach { sp ->
                    recordBotActivity(sp, "idle", task = "Mandate assigned: ${sp.role}", reasoning = sp.systemPrompt.take(260))
                }
                appendTeamFeed(comp.id, "CEO", "Mission '$title' started — objective: $goal")
            }

            // Executes any bot under a watchdog timeout + CEO interrogation + clean Stop/Resume checkpointing
            suspend fun <T> runGuardedBotStep(
                bot: BotItem,
                stepLabel: String,
                customTimeoutMs: Long = botStepTimeoutMs,
                block: suspend () -> T,
            ): Pair<Boolean, T?> {
                if (bot.id in completedIds) return true to null
                if (bot.id == interruptedTargetId) {
                    interruptedTargetId = null
                    ceoInterrogateBlockedBot(
                        comp = comp,
                        ceoBot = ceoBot,
                        stalledBot = bot,
                        reason = "Interrompu à la demande du Fondateur · Reprise au bot suivant",
                        onStatusUpdate = onStatusUpdate,
                    )
                    completedIds.add(bot.id)
                    persistCkpt(null)
                    return true to null
                }
                if (runCancelled || killSwitch) return false to null

                currentRunningBotId = bot.id
                persistCkpt(bot.id)

                val timedResult = withWallTimeoutOrNull(customTimeoutMs) { block() }
                currentRunningBotId = null

                if (runCancelled || killSwitch) {
                    persistCkpt(bot.id)
                    recordBotActivity(bot, "stopped", task = "Stopped by Founder")
                    db.updateBotStatus(bot.id, "idle", "Stopped")
                    appendTeamFeed(comp.id, bot.name, "Stopped mid-step by Founder during '$stepLabel'")
                    db.insertMessage(
                        ChatMessageItem(
                            "msg_${UUID.randomUUID().toString().take(10)}",
                            comp.id,
                            "bot",
                            botId = ceoBot.id,
                            botName = "CEO",
                            content = "⏹️ ${bot.name} arrêté ($stepLabel). Écris « continue » pour que j'interroge ce blocage et lance directement le bot suivant.",
                        )
                    )
                    return false to null
                }

                if (timedResult == null) {
                    agy?.stopActiveRun()
                    ceoInterrogateBlockedBot(
                        comp = comp,
                        ceoBot = ceoBot,
                        stalledBot = bot,
                        reason = "Délai dépassé (${customTimeoutMs / 1000}s) sur $stepLabel",
                        onStatusUpdate = onStatusUpdate,
                    )
                    completedIds.add(bot.id)
                    persistCkpt(null)
                    return true to null
                }

                completedIds.add(bot.id)
                persistCkpt(null)
                return true to timedResult
            }

            // Stage 1: Research, Trends & Data Discovery Bots run sequentially feeding cumulative findings forward
            for (rBot in researchBots) {
                if (runCancelled || killSwitch) return
                if (!hasActiveEngine()) break
                val (proceed, reply) = runGuardedBotStep(rBot, "Recherche (${rBot.role})") {
                    onStatusUpdate("${rBot.name} (${rBot.role}) researching...")
                    recordBotActivity(rBot, "working", task = "Researching: $title", reasoning = "Executing ${rBot.role}...")
                    db.updateBotStatus(rBot.id, "working", "Research & Data...")
                    runDirectBotChat(
                        comp = comp,
                        bot = rBot,
                        userText = "You are ${rBot.name} (${rBot.role}) at '${comp.name}' (Objective: ${comp.description}).\n" +
                            "Use your connected plugins/tools (browser_search, browser_read_url, scite_search, notion_search, gdrive_search, tinyfish_extract) directly to complete your specific research step for '$title', write structured notes/artifacts, and report.\n\nCurrent Brief & Prior Findings:\n${researchDossier.toString().take(2400)}",
                        onStatusUpdate = onStatusUpdate,
                    )
                }
                if (!proceed) return
                if (reply != null) {
                    if (reply.startsWith("⚠️")) {
                        ceoInterrogateBlockedBot(comp, ceoBot, rBot, reply.take(120), onStatusUpdate)
                    } else {
                        researchDossier.append("\n\n### Findings from ${rBot.name} (${rBot.role}):\n").append(reply)
                        recordBotActivity(rBot, "idle", task = "Research complete", reasoning = reply.take(300))
                        db.updateBotStatus(rBot.id, "idle", "Research done", incrementUnread = true)
                        appendTeamFeed(comp.id, rBot.name, reply.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Recherche terminée")
                        db.insertMessage(
                            ChatMessageItem(
                                "msg_${UUID.randomUUID().toString().take(10)}",
                                comp.id,
                                "bot",
                                botId = rBot.id,
                                botName = rBot.name,
                                content = "📋 [${rBot.name} → CEO] ${reply.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Recherche terminée."}",
                            )
                        )
                    }
                }
            }
            val scopedSpec = researchDossier.toString()
            if (researchBots.isNotEmpty() && !runCancelled) {
                sandbox.writeTextFile(comp.id, "workspace/${missionId}_spec.md", scopedSpec)
            }
            if (runCancelled || killSwitch) return

            // Stage 2a: Primary Producer Bot builds core deliverable(s)
            var createdArtifacts = db.listArtifacts(comp.id).filter { it.botName == producerBot.name }.toMutableList()
            val seenOutBeforeProducer = sandbox.listOutputFiles(comp.id).map { it.name }.toMutableSet()
            val producerWasInterrupted = producerBot.id == interruptedTargetId
            val producerTimeout = if (botStepTimeoutMs >= 10_000L) maxOf(botStepTimeoutMs, 240_000L) else botStepTimeoutMs
            val (prodOk, prodBuilt) = runGuardedBotStep(
                bot = producerBot,
                stepLabel = "Production ($title)",
                customTimeoutMs = producerTimeout,
            ) {
                onStatusUpdate("${producerBot.name} is authoring complete deliverables in sandbox...")
                recordBotActivity(producerBot, "working", task = "Building deliverables: $title")
                db.updateBotStatus(producerBot.id, "working", "Authoring book & deliverables...")
                runProducerAgentLoop(comp, producerBot, missionId, title, goal, scopedSpec, onStatusUpdate)
            }
            if (!prodOk) return
            val diskAfterProducer = run {
                prodBuilt?.forEach { seenOutBeforeProducer.add(it.filename) }
                harvestNativeCliArtifacts(comp.id, producerBot.name, seenOutBeforeProducer)
            }
            if (prodBuilt != null || diskAfterProducer.isNotEmpty()) {
                val merged = ((prodBuilt ?: emptyList()) + diskAfterProducer).distinctBy { it.relPath }
                if (merged.isNotEmpty()) {
                    createdArtifacts = merged.toMutableList()
                }
                recordBotActivity(producerBot, "idle", task = "Built ${createdArtifacts.size} artifact(s)")
                db.updateBotStatus(producerBot.id, "idle", "Deliverable ready", incrementUnread = true)
                appendTeamFeed(comp.id, producerBot.name, "Built ${createdArtifacts.joinToString(", ") { it.filename }}")
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        comp.id,
                        "bot",
                        botId = producerBot.id,
                        botName = producerBot.name,
                        content = "🛠️ [${producerBot.name} → CEO] Production terminée : ${createdArtifacts.joinToString(", ") { it.filename }}",
                    )
                )
            } else if (createdArtifacts.isEmpty()) {
                // If Producer timed out (not explicitly skipped by Founder) and an AI engine is active, run a direct 1-shot illustrated book authoring pass
                val aiRecovered = if (!producerWasInterrupted && hasActiveEngine() && !runCancelled && !killSwitch) {
                    val recTimeout = if (botStepTimeoutMs >= 10_000L) maxOf(botStepTimeoutMs, 120_000L) else botStepTimeoutMs
                    withWallTimeoutOrNull(recTimeout) {
                        synthesizeAiBookPdf(comp, producerBot, title, goal, scopedSpec, onStatusUpdate)
                    }
                } else null
                if (aiRecovered != null) {
                    createdArtifacts.add(aiRecovered)
                    appendTeamFeed(comp.id, producerBot.name, "Authored complete illustrated PDF ${aiRecovered.filename}")
                } else {
                    val fallbackArt = buildFallbackPdfArtifact(comp, ceoBot, title, goal, scopedSpec)
                    createdArtifacts.add(fallbackArt)
                    appendTeamFeed(comp.id, "CEO", "Synthesized recovery deliverable ${fallbackArt.filename} to keep mission on track")
                }
            }
            if (runCancelled || killSwitch) return

            // Stage 2b: Visual & Graphic Design Bots generate vector charts, diagrams & visual assets before QA
            for (vBot in visualDesignBots) {
                if (runCancelled || killSwitch) return
                if (!hasActiveEngine()) break
                val (vProceed, vReply) = runGuardedBotStep(vBot, "Design visuel (${vBot.role})") {
                    onStatusUpdate("${vBot.name} designing visuals & charts...")
                    recordBotActivity(vBot, "working", task = "Creating graphics for $title")
                    db.updateBotStatus(vBot.id, "working", "Designing visuals...")
                    runDirectBotChat(
                        comp = comp,
                        bot = vBot,
                        userText = "You are ${vBot.name} (${vBot.role}). Create clean vector charts/diagrams/visuals in output/ using `create_svg_graphic` or `product_design_prototype` (or connected Canva/Figma/Kittl plugins) aligned with '${comp.name}' and '$title'.\nResearch & Deliverable context:\n${scopedSpec.take(1600)}",
                        onStatusUpdate = onStatusUpdate,
                    )
                }
                if (!vProceed) return
                if (vReply != null) {
                    if (vReply.startsWith("⚠️")) {
                        ceoInterrogateBlockedBot(comp, ceoBot, vBot, vReply.take(120), onStatusUpdate)
                    } else {
                        recordBotActivity(vBot, "idle", task = "Visuals ready", reasoning = vReply.take(280))
                        db.updateBotStatus(vBot.id, "idle", "Visuals done", incrementUnread = true)
                        appendTeamFeed(comp.id, vBot.name, vReply.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Visuels créés")
                        db.insertMessage(
                            ChatMessageItem(
                                "msg_${UUID.randomUUID().toString().take(10)}",
                                comp.id,
                                "bot",
                                botId = vBot.id,
                                botName = vBot.name,
                                content = "🎨 [${vBot.name} → CEO] ${vReply.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "Visuels créés."}",
                            )
                        )
                    }
                }
            }
            if (runCancelled || killSwitch) return

            // Stage 3: Independent Quality Auditor checks deliverables (+ Producer revision if score < 70)
            onStatusUpdate("${qaBot.name} is verifying quality...")
            recordBotActivity(qaBot, "working", task = "Auditing ${createdArtifacts.firstOrNull()?.filename ?: title}", tool = "qa_verify", plugin = "Quality Auditor")
            db.updateBotStatus(qaBot.id, "working", "Auditing in sandbox...")

            var primaryArt = createdArtifacts.firstOrNull() ?: db.listArtifacts(comp.id).firstOrNull()
            var qaTuple = withWallTimeoutOrNull(botStepTimeoutMs) { runIndependentQa(comp, qaBot, primaryArt, goal) }
                ?: run {
                    ceoInterrogateBlockedBot(comp, ceoBot, qaBot, "Délai QA dépassé", onStatusUpdate)
                    primaryArt?.let { sandbox.verifyArtifactProgrammatically(comp.id, it.relPath).let { p -> Triple(p.passed, p.score, p.report) } }
                        ?: Triple(false, 40, "QA programmatic fallback")
                }

            if ((!qaTuple.first || qaTuple.second < 70) && hasActiveEngine() && !killSwitch && !runCancelled) {
                onStatusUpdate("${producerBot.name} revising after QA (${qaTuple.second}/100)...")
                recordBotActivity(producerBot, "working", task = "Revising after QA (${qaTuple.second}/100)")
                db.updateBotStatus(producerBot.id, "working", "Fixing QA issues...")
                val revised = withWallTimeoutOrNull(botStepTimeoutMs) {
                    runProducerAgentLoop(
                        comp,
                        producerBot,
                        missionId,
                        title,
                        goal,
                        "QA CRITIQUE (${qaTuple.second}/100): ${qaTuple.third}\nFix every flaw above.\n\nOriginal Spec:\n$scopedSpec",
                        onStatusUpdate,
                    )
                }.orEmpty()
                if (revised.isNotEmpty()) {
                    createdArtifacts = revised.toMutableList()
                    primaryArt = revised.firstOrNull()
                    qaTuple = runIndependentQa(comp, qaBot, primaryArt, goal)
                }
                recordBotActivity(producerBot, "idle", task = "Revision complete")
                db.updateBotStatus(producerBot.id, "idle", "Revised", incrementUnread = true)
            }

            val (qaPassed, qaScore, qaReport) = qaTuple
            recordBotActivity(qaBot, "idle", task = "QA $qaScore/100 · $qaReport", reasoning = qaReport)
            db.updateBotStatus(qaBot.id, "idle", "QA $qaScore/100", incrementUnread = true)
            appendTeamFeed(comp.id, qaBot.name, "QA $qaScore/100 — $qaReport")
            db.insertMessage(
                ChatMessageItem(
                    "msg_${UUID.randomUUID().toString().take(10)}",
                    comp.id,
                    "bot",
                    botId = qaBot.id,
                    botName = qaBot.name,
                    content = "🛡️ [${qaBot.name} → CEO] Audit QA : $qaScore/100 — $qaReport",
                )
            )
            if (runCancelled || killSwitch) return

            // Stage 4: Marketing, Video Studio, Instagram/TikTok Social & 24/7 Customer Care / Sales Bots execute after QA
            for (postBot in postQaSpecialists) {
                if (runCancelled || killSwitch) return
                if (!hasActiveEngine()) break
                val (pProceed, pReply) = runGuardedBotStep(postBot, "Growth/Launch (${postBot.role})") {
                    onStatusUpdate("${postBot.name} (${postBot.role}) running growth/launch...")
                    recordBotActivity(postBot, "working", task = "Executing ${postBot.role} for $title")
                    db.updateBotStatus(postBot.id, "working", "Running ${postBot.role}...")
                    val builtList = sandbox.listOutputFiles(comp.id).joinToString(", ") { it.name }.ifBlank { "none" }
                    runDirectBotChat(
                        comp = comp,
                        bot = postBot,
                        userText = "QA passed ($qaScore/100) for mission '$title' (Goal: $goal).\n" +
                            "Verified sandbox output/ files: $builtList.\n" +
                            "Using your exact role (${postBot.role}) and real tools/plugins (`product_design_prototype` for interactive promo video reels/ads/sales pages, `create_svg_graphic`, `instagram_list_comments`, `instagram_reply_comment`, `instagram_publish_post`, `tiktok_list_videos`, `tiktok_publish_video`, `gmail_*`, `slack_*`, `http_api_call`), execute your marketing/distribution/24-7 customer-care or sales step cleanly. External writes will automatically ask Founder approval via Ship. Report concisely to CEO.",
                        onStatusUpdate = onStatusUpdate,
                    )
                }
                if (!pProceed) return
                if (pReply != null) {
                    if (pReply.startsWith("⚠️")) {
                        ceoInterrogateBlockedBot(comp, ceoBot, postBot, pReply.take(120), onStatusUpdate)
                    } else {
                        recordBotActivity(postBot, "idle", task = "Reported to CEO", reasoning = pReply.take(280))
                        db.updateBotStatus(postBot.id, "idle", "Done · Reported", incrementUnread = true)
                        appendTeamFeed(comp.id, postBot.name, pReply.lineSequence().firstOrNull { it.isNotBlank() }?.take(180) ?: "Tâche exécutée")
                        db.insertMessage(
                            ChatMessageItem(
                                "msg_${UUID.randomUUID().toString().take(10)}",
                                comp.id,
                                "bot",
                                botId = postBot.id,
                                botName = postBot.name,
                                content = "🤖 [${postBot.name} → CEO] ${pReply.lineSequence().firstOrNull { it.isNotBlank() }?.take(220) ?: "Tâche exécutée."}",
                            )
                        )
                    }
                }
            }
            if (runCancelled || killSwitch) return

            // Stage 5: Memory Sync + Artifact Cards + Human Approval Gate
            memoryBot?.let { mBot ->
                val memSummary = "Mission '$title' built by ${producerBot.name}; QA by ${qaBot.name}: $qaScore/100 ($qaReport)"
                recordBotActivity(mBot, "idle", task = "Memory synced ($qaScore/100)", tool = "company_memory", plugin = "Company Memory", reasoning = memSummary)
                db.updateBotStatus(mBot.id, "idle", "Synced", incrementUnread = true)
                appendTeamFeed(comp.id, mBot.name, memSummary)
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        comp.id,
                        "bot",
                        botId = mBot.id,
                        botName = mBot.name,
                        content = "🧠 [${mBot.name} → CEO] Mémoire d'entreprise synchronisée : $memSummary",
                    )
                )
            }

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
            val ledgerEntry = buildString {
                append("- Mission : **$title** ($goal)\n")
                append("- Producteur : ${producerBot.name} · Audit QA (${qaBot.name}) : $qaScore/100 ($qaReport)\n")
                if (createdArtifacts.isNotEmpty()) {
                    append("- Artefacts : ${createdArtifacts.joinToString(", ") { "${it.filename} (${it.sizeBytes}B)" }}\n")
                }
            }
            val notionUrl = syncCompanyNotionLedger(comp, "Mission : $title (QA $qaScore/100)", ledgerEntry)
            if (!notionUrl.isNullOrBlank()) {
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        comp.id,
                        "bot",
                        botName = "CEO",
                        content = "📒 Mémoire Notion mise à jour pour '${comp.name}' : $notionUrl",
                    )
                )
            }

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
                title = if (qaPassed && qaScore >= 60) "Ship ${primaryArt?.title ?: title}?"
                else "⚠️ Review (QA $qaScore/100): ${primaryArt?.title ?: title}",
                subtitle = "QA $qaScore/100 by ${qaBot.name} · ${if (qaPassed) "Verified in on-device sandbox" else qaReport.take(90)}",
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
            clearMissionCheckpoint(comp.id)
        } finally {
            currentRunningBotId = null
            currentMissionCompanyId = null
        }
    }

    private suspend fun runProducerAgentLoop(
        comp: CompanyItem,
        bot: BotItem,
        missionId: String,
        title: String,
        goal: String,
        spec: String,
        onStatusUpdate: suspend (String) -> Unit = {},
    ): List<ArtifactItem> {
        val created = mutableListOf<ArtifactItem>()
        if (!hasActiveEngine()) {
            created.add(buildFallbackPdfArtifact(comp, bot, title, goal, spec))
            return created
        }

        val compRoot = sandbox.companyRoot(comp.id)
        val seenOutNames = sandbox.listOutputFiles(comp.id).map { it.name }.toMutableSet()

        val sysPrompt = """
            You are ${bot.name} (${bot.role}) at company '${comp.name}'.
            Company objective: ${comp.description}
            Your instructions: ${bot.systemPrompt}
            ${pluginExecutor?.activeToolsSummaryForPrompt().orEmpty()}

            You work inside an isolated Android sandbox (${compRoot.absolutePath}).
            ${buildSharedTeamContext(comp.id)}
            CRITICAL AUTHORING RULE:
            - You are creating REAL, COMPLETE, SELLABLE customer-facing digital products — NOT limited to PDFs! Choose the highest-value commercial formats for the target niche & market:
            ${DigitalProductSkills.marketCapabilitiesPrompt()}
            - NEVER copy-paste or echo the Mission Brief or Scope as product content! Write original, comprehensive, customer-ready content. For books/guides/workbooks (`create_pdf`), author 5–8 rich chapters with markdown subheadings (###), **bold**, printable checkboxes (- [ ]), callout quotes (> ...), tables (|...|), worksheet lines (___), and an `illustration` vector chart/diagram per chapter. For spreadsheets (`create_xlsx`), include multiple tabs with real Excel formulas (=SUM, =AVERAGE, =B2-C2). For slides (`create_pptx`), author 6–10 structured slides with KPI callouts. For editable templates (`create_docx`), author ready-to-customize sections. For Notion (`create_notion_pack`), include structured Markdown pages + CSV databases. Always create a crisp cover/listing graphic (`create_png_graphic`) and package everything cleanly (`create_zip`) when shipping a bundle!
            Reply strictly with ONE JSON object per step:
            - Call 1 tool: {"action": "tool", "tool": "<tool_name>", "args": {...}}
            - Call multiple tools at once: {"action": "tools", "calls": [{"tool": "...", "args": {...}}]}
              Sellable Product & Sandbox Tools:
              * "create_pdf": {"filename": "${slugify(title)}.pdf", "title": "...", "subtitle": "...", "author": "${comp.name}", "edition_badge": "COMPLETE EDITION", "theme": "executive|emerald|editorial|minimal|wellness", "highlights": ["..."], "chapters": [{"heading": "...", "summary": "...", "body": "...", "illustration": {"type": "bar_chart|roadmap|kpi_grid|framework|habit_tracker|checklist_card", "title": "...", "items": [{"label": "...", "value": 85, "detail": "85%"}]}}]}
              * "create_xlsx": {"filename": "${slugify(title)}.xlsx", "sheets": [{"name": "Dashboard", "headers": ["Category", "Budget", "Actual", "Diff"], "rows": [["Housing", "1500", "1450", "=B2-C2"], ["Total", "=SUM(B2:B2)", "=SUM(C2:C2)", "=SUM(D2:D2)"]]}]}
              * "create_pptx": {"filename": "${slugify(title)}.pptx", "title": "...", "subtitle": "...", "theme": "executive|emerald|editorial|minimal|wellness", "slides": [{"title": "...", "subtitle": "...", "bullets": ["..."], "highlight_stat": "92%", "highlight_label": "..."}]}
              * "create_docx": {"filename": "${slugify(title)}.docx", "title": "...", "subtitle": "...", "sections": [{"heading": "...", "body": "..."}]}
              * "create_png_graphic": {"filename": "cover_mockup.png", "title": "...", "subtitle": "...", "badge": "2027 BUNDLE", "bullets": ["..."], "price_tag": "$29", "theme": "wellness", "width": 1200, "height": 1500}
              * "create_notion_pack": {"filename": "${slugify(title)}_notion_pack.zip", "title": "...", "setup_guide": "...", "pages": [{"title": "...", "body": "..."}], "databases": [{"name": "Tasks", "headers": ["Task", "Status", "Priority"], "rows": [["Define Q1 goals", "To Do", "High"]]}]}
              * "create_csv_dataset": {"filename": "dataset.csv", "headers": ["..."], "rows": [["..."]]}
              * "create_web_app": {"filename": "app.html", "title": "...", "subtitle": "...", "theme": "minimal", "html": "<!DOCTYPE html>..."}
              * "create_svg_graphic": {"filename": "chart.svg", "title": "...", "svg": "<svg ...>...</svg>"}
              * "create_zip": {"zip_filename": "${slugify(title)}_bundle.zip", "rel_paths": [], "readme": "Thank you for purchasing..."}
              * "list_files": {} | "read_file": {"rel_path": "..."} | "write_file": {"rel_path": "output/...", "content": "..."}
              * "product_design_prototype": {"filename": "prototype.html", "title": "...", "html": "..."}
              Or ANY connected plugin tool above!
            - Finish once deliverables exist in output/:
              {"action": "final", "summary": "...", "primary_rel_path": "output/..."}
        """.trimIndent()

        val promptHistory = mutableListOf("Mission: $title\nGoal: $goal\nSpec:\n$spec")
        var lastAiText = ""

        for (step in 1..6) {
            if (killSwitch || runCancelled) break
            onStatusUpdate("${bot.name} executing step $step/6...")
            val res = runAiCompletion(
                systemPrompt = sysPrompt,
                userPrompt = promptHistory.joinToString("\n\n"),
                sandboxDir = compRoot,
                modelSpec = bot.model,
                jsonMode = !canUseAgyEngine(),
                maxTokens = 8192,
                onStatus = onStatusUpdate,
            )
            if (!res.ok) break
            db.addTokensUsed(comp.id, res.tokensUsed)
            lastAiText = res.content

            // Pick up any native output/ files written directly by Antigravity CLI I/O
            created.addAll(harvestNativeCliArtifacts(comp.id, bot.name, seenOutNames))

            val parsed = llm.extractActionOrToolCall(res.content) ?: break
            val action = parsed.optString("action").lowercase()
            val toolName = parsed.optString("tool")
            when {
                action == "tools" || parsed.has("calls") -> {
                    val (obs, arts) = executeToolOrBatch(comp, bot, parsed, onStatusUpdate)
                    arts.forEach { art ->
                        seenOutNames.add(art.filename)
                        created.add(art)
                        appendTeamFeed(comp.id, bot.name, "Produced artifact ${art.filename} (${art.relPath})")
                    }
                    created.addAll(harvestNativeCliArtifacts(comp.id, bot.name, seenOutNames))
                    promptHistory.add("Step $step ran tool batch")
                    promptHistory.add("Observation:\n$obs")
                }
                action == "tool" || toolName.isNotBlank() -> {
                    val chosenTool = toolName.ifBlank { "create_pdf" }
                    val args = parsed.optJSONObject("args") ?: JSONObject()
                    val (obs, art) = executeAnyTool(comp, bot, chosenTool, args, onStatusUpdate)
                    if (art != null) {
                        seenOutNames.add(art.filename)
                        created.add(art)
                        appendTeamFeed(comp.id, bot.name, "Produced artifact ${art.filename} (${art.relPath})")
                    }
                    created.addAll(harvestNativeCliArtifacts(comp.id, bot.name, seenOutNames))
                    promptHistory.add("Step $step called $chosenTool")
                    promptHistory.add("Observation:\n$obs")
                }
                (parsed.has("chapters") || parsed.has("sections")) && created.isEmpty() -> {
                    val (_, art) = executeAnyTool(comp, bot, "create_pdf", parsed.put("filename", "${slugify(title)}.pdf"), onStatusUpdate)
                    if (art != null) {
                        seenOutNames.add(art.filename)
                        created.add(art)
                    }
                    break
                }
                else -> break
            }
        }

        if (created.isEmpty() && lastAiText.length > 80 && !killSwitch && !runCancelled) {
            val maybeObj = llm.extractJsonObject(lastAiText)
            if (maybeObj != null && (maybeObj.has("chapters") || maybeObj.has("sections"))) {
                executeSandboxTool(comp, bot, "create_pdf", maybeObj.put("filename", "${slugify(title)}.pdf")).second?.let { created.add(it) }
            } else {
                val bookSpec = PdfBookRenderer.parseMarkdownIntoBook(
                    title = title,
                    subtitle = "${comp.name} · Complete Illustrated Edition",
                    author = comp.name,
                    markdown = lastAiText.take(36_000),
                )
                val file = sandbox.createIllustratedBookPdf(
                    companyId = comp.id,
                    filename = "${slugify(title)}.pdf",
                    spec = bookSpec,
                )
                created.add(registerArtifact(comp.id, bot.name, title, file, "Illustrated PDF book (${bookSpec.chapters.size} chapters)"))
            }
        }

        if (created.isEmpty() && !killSwitch && !runCancelled) {
            synthesizeAiBookPdf(comp, bot, title, goal, spec, onStatusUpdate)?.let { created.add(it) }
        }

        if (created.isEmpty() && !killSwitch && !runCancelled) {
            created.add(buildFallbackPdfArtifact(comp, bot, title, goal, spec))
        }
        return created
    }

    private suspend fun synthesizeAiBookPdf(
        comp: CompanyItem,
        bot: BotItem,
        title: String,
        goal: String,
        spec: String,
        onStatusUpdate: suspend (String) -> Unit = {},
    ): ArtifactItem? {
        onStatusUpdate("${bot.name} authoring illustrated PDF book...")
        val secRes = runAiCompletion(
            systemPrompt = """
                You are a bestselling book & workbook author at '${comp.name}'.
                Author a COMPLETE, SELLABLE, ILLUSTRATED publication in valid JSON ONLY (no markdown outside JSON).
                NEVER repeat or copy the mission brief. Write real, original, actionable chapters for the end reader in the target language requested by the goal.
                Required JSON schema:
                {
                  "title": "Publication Title",
                  "subtitle": "Compelling subtitle",
                  "author": "${comp.name}",
                  "edition_badge": "COMPLETE EDITION",
                  "theme": "executive|emerald|editorial|minimal|wellness",
                  "highlights": ["Highlight 1", "Highlight 2", "Highlight 3", "Highlight 4"],
                  "chapters": [
                    {
                      "heading": "Chapter Title",
                      "summary": "1-sentence chapter summary",
                      "body": "Full multi-paragraph chapter with ### Subheadings, **bold** emphasis, - bullets, - [ ] printable checkboxes, > callout quotes, | Markdown | Tables |, and ___ worksheet lines.",
                      "illustration": {
                        "type": "bar_chart|roadmap|kpi_grid|framework|habit_tracker|checklist_card",
                        "title": "Diagram title",
                        "items": [{"label": "Item", "value": 80, "detail": "80%"}]
                      }
                    }
                  ]
                }
                Include at least 6 thorough chapters, each with substantial customer-ready content and a relevant illustration.
            """.trimIndent(),
            userPrompt = "Title: $title\nCompany: ${comp.name}\nGoal & Requirements:\n$goal\n\nResearch Context:\n${spec.take(3500)}",
            sandboxDir = sandbox.companyRoot(comp.id),
            modelSpec = bot.model,
            jsonMode = true,
            maxTokens = 8192,
            onStatus = onStatusUpdate,
        )
        if (!secRes.ok || secRes.content.isBlank()) return null
        db.addTokensUsed(comp.id, secRes.tokensUsed)
        val obj = llm.extractJsonObject(secRes.content)
        if (obj != null && (obj.has("chapters") || obj.has("sections"))) {
            return executeSandboxTool(comp, bot, "create_pdf", obj.put("filename", "${slugify(title)}.pdf")).second
        }
        if (secRes.content.length > 250) {
            val bookSpec = PdfBookRenderer.parseMarkdownIntoBook(
                title = title,
                subtitle = "${comp.name} · Complete Illustrated Edition",
                author = comp.name,
                markdown = secRes.content.take(36_000),
            )
            val file = sandbox.createIllustratedBookPdf(comp.id, "${slugify(title)}.pdf", bookSpec)
            return registerArtifact(comp.id, bot.name, title, file, "Illustrated PDF book (${bookSpec.chapters.size} chapters)")
        }
        return null
    }

    private suspend fun executeAnyTool(
        comp: CompanyItem,
        bot: BotItem,
        tool: String,
        args: JSONObject,
        onStatusUpdate: suspend (String) -> Unit = {},
    ): Pair<String, ArtifactItem?> {
        val pluginDef = PluginRegistry.ALL.firstOrNull { p -> p.tools.any { it.name == tool } }
        val pluginName = pluginDef?.name ?: "Sandbox Core"
        val resolvedUrl = resolveToolDisplayUrl(tool, args)
        val ts = System.currentTimeMillis()

        recordBotActivity(
            bot = bot,
            status = "working",
            task = "Running $pluginName · $tool",
            plugin = pluginName,
            tool = tool,
            url = resolvedUrl,
        )
        appendReasoning("\n[${bot.name} → $pluginName | $tool${if (resolvedUrl.isNotBlank()) " | $resolvedUrl" else ""}]\n")

        val exec = pluginExecutor
        if (exec != null && exec.isPluginTool(tool)) {
            onStatusUpdate("${bot.name} · $pluginName → $tool")
            val res = exec.execute(comp.id, tool, args, allowWriteExecution = false)
            if (res.requiresApproval) {
                val aprId = "apr_${UUID.randomUUID().toString().take(10)}"
                val payload = JSONObject()
                    .put("plugin_tool", tool)
                    .put("plugin_args", args)
                    .put("completion_message", "Executed plugin '$tool' for ${comp.name}.")
                val approval = ApprovalItem(
                    id = aprId,
                    companyId = comp.id,
                    title = res.approvalTitle.ifBlank { "Run $tool?" },
                    subtitle = "${bot.name} · ${res.approvalSummary}",
                    actionType = "PLUGIN_EXEC",
                    actionPayloadJson = payload.toString(),
                    status = "PENDING",
                )
                db.insertApproval(approval)
                db.insertMessage(
                    ChatMessageItem(
                        "msg_${UUID.randomUUID().toString().take(10)}",
                        comp.id,
                        "bot",
                        botName = "CEO",
                        content = "${bot.name} wants to run `$tool`. Approve to execute?",
                        cardType = "approval",
                    ),
                    rawPayloadJson = JSONObject().put("id", aprId).toString(),
                )
                ApprovalNotifier.showApprovalNotification(context, approval)
            }
            val objOut = runCatching { JSONObject(res.output) }.getOrNull()
            val relPath = objOut?.optString("rel_path").orEmpty()
            var createdArt: ArtifactItem? = null
            if (relPath.startsWith("output/")) {
                val f = sandbox.resolveFile(comp.id, relPath)
                if (f.exists()) {
                    createdArt = registerArtifact(comp.id, bot.name, f.nameWithoutExtension, f, "Generated via $tool")
                }
            }
            recordBotActivity(
                bot = bot,
                status = "working",
                task = "Completed $tool",
                plugin = pluginName,
                tool = tool,
                url = resolvedUrl,
                addTrace = BotActionTrace(ts, tool, pluginName, resolvedUrl, res.output.take(180), res.ok),
            )
            return res.output to createdArt
        }
        val (out, art) = executeSandboxTool(comp, bot, tool, args)
        val okFlag = !out.contains("\"ok\":false", ignoreCase = true)
        recordBotActivity(
            bot = bot,
            status = "working",
            task = "Completed $tool",
            plugin = pluginName,
            tool = tool,
            url = resolvedUrl,
            addTrace = BotActionTrace(ts, tool, pluginName, resolvedUrl, out.take(180), okFlag),
        )
        return out to art
    }

    private fun resolveToolDisplayUrl(tool: String, args: JSONObject): String {
        val explicit = args.optString("url")
        if (explicit.startsWith("http")) return explicit
        return when {
            tool == "browser_search" -> "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(args.optString("query"), "UTF-8")}"
            tool.startsWith("github_") -> {
                val o = args.optString("owner")
                val r = args.optString("repo")
                if (o.isNotBlank() && r.isNotBlank()) "https://github.com/$o/$r" else "https://github.com"
            }
            tool.startsWith("notion_") -> "https://www.notion.so"
            tool.startsWith("slack_") -> "https://app.slack.com"
            tool.startsWith("gmail_") -> "https://mail.google.com"
            tool.startsWith("gdrive_") -> "https://drive.google.com"
            tool.startsWith("figma_") -> "https://www.figma.com"
            tool.startsWith("canva_") -> "https://www.canva.com"
            tool.startsWith("kittl_") -> "https://www.kittl.com/projects"
            tool.startsWith("scite_") -> "https://scite.ai"
            tool.startsWith("tinyfish_") -> args.optString("url").ifBlank { "https://app.tinyfish.io" }
            else -> ""
        }
    }

    private fun emitArtifactCard(comp: CompanyItem, botName: String, art: ArtifactItem) {
        val payload = JSONObject()
            .put("id", art.id)
            .put("bot_name", botName)
            .put("title", art.title)
            .put("filename", art.filename)
            .put("rel_path", art.relPath)
            .put("mime_type", art.mimeType)
            .put("size_bytes", art.sizeBytes)
            .put("summary", art.summary)
            .put("qa_status", art.qaStatus)
            .put("qa_score", art.qaScore)
            .put("qa_report", art.qaReport)
        db.insertMessage(
            ChatMessageItem(
                "msg_${UUID.randomUUID().toString().take(10)}",
                comp.id,
                "bot",
                botName = botName,
                content = "$botName created ${art.filename}",
                cardType = "artifact",
            ),
            rawPayloadJson = payload.toString(),
        )
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
                val sub = args.optString("subtitle").ifBlank { "Complete Edition · ${comp.name}" }
                val author = args.optString("author").ifBlank { comp.name }
                val editionBadge = args.optString("edition_badge").ifBlank { "COMPLETE EDITION" }
                val theme = args.optString("theme").ifBlank { PdfBookRenderer.inferTheme(docTitle, sub) }
                val hlArr = args.optJSONArray("highlights") ?: args.optJSONArray("cover_highlights") ?: JSONArray()
                val highlights = (0 until hlArr.length()).map { hlArr.optString(it).trim() }.filter { it.isNotBlank() }

                val chapArr = args.optJSONArray("chapters") ?: args.optJSONArray("sections") ?: JSONArray()
                val chapters = mutableListOf<PdfBookChapter>()
                for (i in 0 until chapArr.length()) {
                    val s = chapArr.optJSONObject(i) ?: continue
                    val heading = s.optString("heading").ifBlank { s.optString("title", "Chapter ${i + 1}") }
                    val body = s.optString("body").ifBlank { s.optString("content", "") }
                    val summary = s.optString("summary").trim()
                    val visObj = s.optJSONObject("illustration") ?: s.optJSONObject("visual") ?: s.optJSONObject("chart")
                    val illustration = if (visObj != null) {
                        val itemsArr = visObj.optJSONArray("items") ?: JSONArray()
                        val visItems = (0 until itemsArr.length()).mapNotNull { j ->
                            val itObj = itemsArr.optJSONObject(j)
                            if (itObj != null) {
                                val lbl = itObj.optString("label").ifBlank { itObj.optString("name") }.trim()
                                if (lbl.isBlank()) null
                                else PdfVisualItem(
                                    label = lbl,
                                    value = itObj.optDouble("value", 0.0).toFloat(),
                                    detail = itObj.optString("detail").ifBlank { itObj.optString("text") }.trim(),
                                )
                            } else {
                                val str = itemsArr.optString(j).trim()
                                if (str.isBlank()) null else PdfVisualItem(label = str)
                            }
                        }
                        if (visItems.isNotEmpty()) {
                            PdfIllustrationSpec(
                                type = visObj.optString("type", "framework"),
                                title = visObj.optString("title", "$heading — Visual"),
                                items = visItems,
                            )
                        } else null
                    } else {
                        PdfBookRenderer.autoSynthesizeIllustration(heading, body)
                    }
                    chapters.add(
                        PdfBookChapter(
                            heading = heading,
                            body = body,
                            summary = summary,
                            illustration = illustration,
                        )
                    )
                }
                if (chapters.isEmpty()) {
                    val rawContent = args.optString("content", comp.description)
                    val parsedBook = PdfBookRenderer.parseMarkdownIntoBook(docTitle, sub, author, rawContent, theme)
                    chapters.addAll(parsedBook.chapters)
                }
                val bookSpec = PdfBookSpec(
                    title = docTitle,
                    subtitle = sub,
                    author = author,
                    editionBadge = editionBadge,
                    theme = theme,
                    highlights = highlights.ifEmpty { chapters.take(4).map { it.heading } },
                    chapters = chapters,
                )
                val file = sandbox.createIllustratedBookPdf(comp.id, fn, bookSpec)
                val art = registerArtifact(
                    comp.id,
                    bot.name,
                    docTitle,
                    file,
                    "Illustrated PDF book (${chapters.size} chapters, ${theme} theme)",
                )
                JSONObject()
                    .put("ok", true)
                    .put("rel_path", art.relPath)
                    .put("bytes", art.sizeBytes)
                    .put("chapters", chapters.size)
                    .toString() to art
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
                val zName = args.optString("zip_filename").ifBlank { args.optString("filename") }.ifBlank { "${slugify(comp.name)}_pack.zip" }
                val arr = args.optJSONArray("rel_paths") ?: JSONArray()
                val requestedPaths = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                val paths = requestedPaths.ifEmpty {
                    sandbox.listOutputFiles(comp.id)
                        .filterNot { it.name.endsWith(".zip", ignoreCase = true) }
                        .map { "output/${it.name}" }
                }
                val readme = args.optString("readme").trim().ifBlank {
                    "Thank you for purchasing from ${comp.name}!\n\nIncluded files:\n" +
                        paths.joinToString("\n") { "- ${it.substringAfterLast('/')}" }
                }
                val file = sandbox.createZipBundle(comp.id, zName, paths, readmeText = readme)
                val art = registerArtifact(comp.id, bot.name, zName, file, "Sellable ZIP bundle (${paths.size} files)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_xlsx" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_tracker.xlsx" }
                val sheets = DigitalProductFactory.parseSheetsFromJson(args.optJSONArray("sheets"))
                    .ifEmpty {
                        val hArr = args.optJSONArray("headers") ?: JSONArray()
                        val rArr = args.optJSONArray("rows") ?: JSONArray()
                        val headers = (0 until hArr.length()).map { hArr.optString(it) }.ifEmpty { listOf("Item", "Category", "Amount") }
                        val rows = (0 until rArr.length()).mapNotNull { rIdx ->
                            val row = rArr.optJSONArray(rIdx) ?: return@mapNotNull null
                            (0 until row.length()).map { cIdx -> row.opt(cIdx)?.toString().orEmpty() }
                        }
                        listOf(DigitalProductFactory.SheetSpec(args.optString("sheet_name", "Sheet1"), headers, rows))
                    }
                val file = sandbox.createXlsx(comp.id, fn, sheets)
                val art = registerArtifact(comp.id, bot.name, args.optString("title").ifBlank { file.nameWithoutExtension }, file, "Excel/Sheets workbook (${sheets.size} sheets)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).put("sheets", sheets.size).toString() to art
            }
            "create_pptx" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_deck.pptx" }
                val deckTitle = args.optString("title").ifBlank { comp.name }
                val deckSub = args.optString("subtitle").ifBlank { "Presentation · ${comp.name}" }
                val theme = args.optString("theme").ifBlank { PdfBookRenderer.inferTheme(deckTitle, deckSub) }
                val sArr = args.optJSONArray("slides") ?: JSONArray()
                val slides = (0 until sArr.length()).mapNotNull { i ->
                    val o = sArr.optJSONObject(i) ?: return@mapNotNull null
                    val bArr = o.optJSONArray("bullets") ?: o.optJSONArray("points") ?: JSONArray()
                    val bullets = (0 until bArr.length()).map { bArr.optString(it) }.filter { it.isNotBlank() }
                    DigitalProductFactory.SlideSpec(
                        title = o.optString("title", "Slide ${i + 1}"),
                        subtitle = o.optString("subtitle"),
                        bullets = bullets,
                        highlightStat = o.optString("highlight_stat").ifBlank { o.optString("stat") },
                        highlightLabel = o.optString("highlight_label").ifBlank { o.optString("stat_label") },
                    )
                }.ifEmpty {
                    listOf(DigitalProductFactory.SlideSpec("Overview", deckSub, listOf(comp.description)))
                }
                val file = sandbox.createPptx(comp.id, fn, deckTitle, deckSub, comp.name, theme, slides)
                val art = registerArtifact(comp.id, bot.name, deckTitle, file, "PowerPoint/Slides deck (${slides.size + 1} slides)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).put("slides", slides.size + 1).toString() to art
            }
            "create_docx" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_template.docx" }
                val docTitle = args.optString("title").ifBlank { comp.name }
                val docSub = args.optString("subtitle").ifBlank { "Editable Template · ${comp.name}" }
                val secArr = args.optJSONArray("sections") ?: args.optJSONArray("chapters") ?: JSONArray()
                val sections = (0 until secArr.length()).mapNotNull { i ->
                    val o = secArr.optJSONObject(i) ?: return@mapNotNull null
                    val h = o.optString("heading").ifBlank { o.optString("title", "Section ${i + 1}") }
                    val b = o.optString("body").ifBlank { o.optString("content", "") }
                    h to b
                }.ifEmpty {
                    listOf("Overview" to args.optString("content", comp.description))
                }
                val file = sandbox.createDocx(comp.id, fn, docTitle, docSub, sections)
                val art = registerArtifact(comp.id, bot.name, docTitle, file, "Word/Docs editable template (${sections.size} sections)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_png_graphic" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_cover.png" }
                val gTitle = args.optString("title").ifBlank { comp.name }
                val gSub = args.optString("subtitle")
                val badge = args.optString("badge")
                val bArr = args.optJSONArray("bullets") ?: args.optJSONArray("highlights") ?: JSONArray()
                val bullets = (0 until bArr.length()).map { bArr.optString(it) }.filter { it.isNotBlank() }
                val priceTag = args.optString("price_tag")
                val theme = args.optString("theme").ifBlank { PdfBookRenderer.inferTheme(gTitle, gSub) }
                val w = args.optInt("width", 1200)
                val h = args.optInt("height", 1500)
                val file = sandbox.createPngGraphic(comp.id, fn, gTitle, gSub, badge, bullets, priceTag, theme, w, h)
                val art = registerArtifact(comp.id, bot.name, gTitle, file, "High-res PNG cover/graphic (${w}x${h})")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_notion_pack" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_notion_pack.zip" }
                val pTitle = args.optString("title").ifBlank { "${comp.name} Notion OS" }
                val guide = args.optString("setup_guide")
                val pArr = args.optJSONArray("pages") ?: JSONArray()
                val pages = (0 until pArr.length()).mapNotNull { i ->
                    val o = pArr.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("title", "Page ${i + 1}") to o.optString("body").ifBlank { o.optString("content", "") }
                }
                val dbs = DigitalProductFactory.parseSheetsFromJson(args.optJSONArray("databases"))
                val file = sandbox.createNotionPack(comp.id, fn, pTitle, guide, pages, dbs)
                val art = registerArtifact(comp.id, bot.name, pTitle, file, "Notion template pack (${pages.size} pages, ${dbs.size} DBs)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_csv_dataset" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_dataset.csv" }
                val hArr = args.optJSONArray("headers") ?: args.optJSONArray("columns") ?: JSONArray()
                val headers = (0 until hArr.length()).map { hArr.optString(it) }
                val rArr = args.optJSONArray("rows") ?: JSONArray()
                val rows = (0 until rArr.length()).mapNotNull { rIdx ->
                    val row = rArr.optJSONArray(rIdx) ?: return@mapNotNull null
                    (0 until row.length()).map { cIdx -> row.opt(cIdx)?.toString().orEmpty() }
                }
                val file = sandbox.createCsv(comp.id, fn, headers, rows)
                val art = registerArtifact(comp.id, bot.name, args.optString("title").ifBlank { file.nameWithoutExtension }, file, "CSV dataset (${rows.size} rows)")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_web_app" -> {
                val fn = args.optString("filename").ifBlank { "${slugify(comp.name)}_app.html" }
                val aTitle = args.optString("title").ifBlank { comp.name }
                val aSub = args.optString("subtitle")
                val theme = args.optString("theme").ifBlank { PdfBookRenderer.inferTheme(aTitle, aSub) }
                val html = args.optString("html").ifBlank { args.optString("content", "<p>${comp.description}</p>") }
                val file = sandbox.createWebApp(comp.id, fn, aTitle, aSub, html, theme)
                val art = registerArtifact(comp.id, bot.name, aTitle, file, "Interactive HTML5 web app/template")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "create_svg_graphic" -> {
                val fnRaw = args.optString("filename").ifBlank { "${slugify(comp.name)}_graphic.svg" }
                val cleanFn = fnRaw.replace(Regex("[^a-zA-Z0-9._-]"), "_").let { if (it.endsWith(".svg", true)) it else "$it.svg" }
                val title = args.optString("title").ifBlank { "Visual Graphic" }
                val rawSvg = args.optString("svg").trim()
                val svgContent = if (rawSvg.contains("<svg", ignoreCase = true)) rawSvg
                else """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 480" width="800" height="480"><rect width="800" height="480" rx="24" fill="#F8FAFC"/><text x="48" y="72" font-family="system-ui,sans-serif" font-size="28" font-weight="700" fill="#0F172A">$title</text><rect x="48" y="120" width="704" height="296" rx="16" fill="#FFFFFF" stroke="#E2E8F0"/><text x="72" y="180" font-family="system-ui,sans-serif" font-size="16" fill="#334155">${args.optString("summary", comp.description).take(120)}</text></svg>"""
                val file = sandbox.writeTextFile(comp.id, "output/$cleanFn", svgContent)
                val art = registerArtifact(comp.id, bot.name, title, file, "Vector SVG graphic/chart")
                JSONObject().put("ok", true).put("rel_path", art.relPath).put("bytes", art.sizeBytes).toString() to art
            }
            "assign_bot_prompt" -> {
                val targetName = args.optString("bot_name").trim()
                val newRole = args.optString("role").trim()
                val newPrompt = args.optString("system_prompt").trim()
                val targetBot = db.listBots(comp.id).firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    ?: return JSONObject().put("ok", false).put("error", "Bot '$targetName' not found").toString() to null
                val finalRole = newRole.ifBlank { targetBot.role }
                val finalPrompt = enrichBotSystemPrompt(
                    botName = targetBot.name,
                    role = finalRole,
                    companyName = comp.name,
                    companyObjective = comp.description,
                    rawPrompt = newPrompt.ifBlank { targetBot.systemPrompt },
                    allowedTools = targetBot.allowedTools,
                    isQa = targetBot.isQa,
                )
                db.updateBotRoleAndPrompt(targetBot.id, finalRole, finalPrompt)
                db.rememberNote("mem_${UUID.randomUUID().toString().take(8)}", comp.id, "CEO", "ceo_mandate", "${targetBot.name} -> $finalRole")
                recordBotActivity(targetBot, "idle", task = "CEO updated role: $finalRole", reasoning = finalPrompt.take(280))
                JSONObject().put("ok", true).put("bot", targetBot.name).put("role", finalRole).put("system_prompt", finalPrompt).toString() to null
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
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "png" -> "image/png"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "svg" -> "image/svg+xml"
            "html" -> "text/html"
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
        val hasDistinctResearch = spec.isNotBlank() && spec.trim() != goal.trim()
        val sections = mutableListOf<Pair<String, String>>()
        sections.add("Executive Overview" to goal)
        if (hasDistinctResearch) {
            sections.add("Research & Deliverable Plan" to spec.removePrefix(goal).trim().ifBlank { spec })
        }
        val statusNote = if (hasActiveEngine()) {
            "This recovery document was assembled inside sandbox '${comp.name}' because the authoring step was interrupted or timed out. Send 'continue' or tap Regenerate to author the full illustrated edition."
        } else {
            "This local preview was generated inside sandbox '${comp.name}'. Connect Antigravity CLI or an LLM provider in Settings to unlock full AI book authoring."
        }
        sections.add("Production Status" to statusNote)

        val file = sandbox.createPdf(
            companyId = comp.id,
            filename = "${slugify(title)}.pdf",
            title = title,
            subtitle = "${comp.name} · ${bot.name}",
            sections = sections,
            author = comp.name,
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

    private suspend fun executeApprovedAction(companyId: String, actionType: String, payload: JSONObject): String {
        if (actionType == "PLUGIN_EXEC") {
            val tool = payload.optString("plugin_tool")
            val args = payload.optJSONObject("plugin_args") ?: JSONObject()
            val exec = pluginExecutor ?: return "Plugin executor unavailable."
            val res = exec.execute(companyId, tool, args, allowWriteExecution = true)
            return if (res.ok) "✅ $tool executed: ${res.output.take(180)}" else "❌ $tool failed: ${res.output.take(180)}"
        }
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
