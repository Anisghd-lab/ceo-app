package com.foundryos.app.data

import android.content.Context
import android.os.Environment
import com.foundryos.app.service.ApprovalNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class FoundryRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("foundry_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var webSocket: WebSocket? = null

    var serverUrl: String
        get() = prefs.getString("server_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    var pairingToken: String
        get() = prefs.getString("pairing_token", "") ?: ""
        set(value) = prefs.edit().putString("pairing_token", value.trim()).apply()

    var isLiveConnected: Boolean = false
        private set

    // In-memory local interactive sandbox fallback when backend isn't reachable yet
    private val localCompanies = mutableListOf<CompanyItem>()
    private val localMessages = mutableMapOf<String, MutableList<ChatMessageItem>>()
    private val localArtifacts = mutableMapOf<String, MutableList<ArtifactItem>>()
    private val localApprovals = mutableListOf<ApprovalItem>()
    private val localConnectors = mutableMapOf<String, MutableList<ConnectorItem>>()

    private fun reqBuilder(path: String): Request.Builder {
        return Request.Builder()
            .url("${serverUrl.trimEnd('/')}$path")
            .header("X-Foundry-Token", pairingToken)
    }

    suspend fun checkHealth(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${serverUrl.trimEnd('/')}/api/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    isLiveConnected = true
                    val body = JSONObject(resp.body?.string() ?: "{}")
                    val provs = body.optJSONArray("providers")
                    val provCount = provs?.length() ?: 0
                    return@withContext true to "Connected · $provCount LLM provider(s)"
                }
                isLiveConnected = false
                false to "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            isLiveConnected = false
            false to "Offline (Interactive Local Preview)"
        }
    }

    fun connectWebSocket(onEvent: (String, String?) -> Unit) {
        webSocket?.cancel()
        if (!isLiveConnected || pairingToken.isBlank()) return
        val wsUrl = serverUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://") + "/ws?token=$pairingToken"
        val req = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    val type = obj.optString("type")
                    val cid = obj.optString("company_id").ifBlank { null }
                    if (type == "approval_created") {
                        val payload = obj.optJSONObject("payload")
                        if (payload != null) {
                            ApprovalNotifier.showApprovalNotification(context, parseApproval(payload))
                        }
                    }
                    onEvent(type, cid)
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        })
    }

    fun closeWebSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    suspend fun fetchCompanies(): List<CompanyItem> = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                client.newCall(reqBuilder("/api/companies").get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val arr = root.optJSONArray("companies") ?: JSONArray()
                        return@withContext (0 until arr.length()).map { parseCompany(arr.getJSONObject(it)) }
                    }
                }
            } catch (_: Exception) {
                isLiveConnected = false
            }
        }
        localCompanies.toList()
    }

    suspend fun fetchCompanyDetail(companyId: String): Triple<List<BotItem>, List<ChatMessageItem>, List<ConnectorItem>> =
        withContext(Dispatchers.IO) {
            if (isLiveConnected) {
                try {
                    client.newCall(reqBuilder("/api/companies/$companyId").get().build()).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val root = JSONObject(resp.body?.string() ?: "{}")
                            val botsArr = root.optJSONArray("bots") ?: JSONArray()
                            val msgsArr = root.optJSONArray("messages") ?: JSONArray()
                            val connArr = root.optJSONArray("connectors") ?: JSONArray()
                            val bots = (0 until botsArr.length()).map { parseBot(botsArr.getJSONObject(it)) }
                            val msgs = (0 until msgsArr.length()).map { parseMessage(msgsArr.getJSONObject(it)) }
                            val conns = (0 until connArr.length()).map {
                                val o = connArr.getJSONObject(it)
                                ConnectorItem(
                                    id = o.optString("id"),
                                    name = o.optString("name"),
                                    baseUrl = o.optString("base_url"),
                                    liveEnabled = o.optInt("live_enabled") == 1,
                                )
                            }
                            return@withContext Triple(bots, msgs, conns)
                        }
                    }
                } catch (_: Exception) {
                }
            }
            val comp = localCompanies.firstOrNull { it.id == companyId }
            Triple(
                comp?.bots ?: emptyList(),
                localMessages[companyId]?.toList() ?: emptyList(),
                localConnectors[companyId]?.toList() ?: emptyList(),
            )
        }

    suspend fun proposeCompany(description: String): CompanyProposalData = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                val body = JSONObject().put("description", description).toString()
                client.newCall(reqBuilder("/api/companies/propose").post(body.toRequestBody(jsonMedia)).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) {
                            val p = JSONObject(resp.body?.string() ?: "{}").getJSONObject("proposal")
                            return@withContext parseProposal(p)
                        }
                    }
            } catch (_: Exception) {
            }
        }
        synthesizeLocalProposal(description)
    }

    suspend fun deployCompany(description: String, proposal: CompanyProposalData): CompanyItem = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                val payload = JSONObject()
                    .put("description", description)
                    .put("proposal", JSONObject(proposal.rawProposalJson))
                    .toString()
                client.newCall(reqBuilder("/api/companies").post(payload.toRequestBody(jsonMedia)).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) {
                            val root = JSONObject(resp.body?.string() ?: "{}")
                            val c = root.getJSONObject("company")
                            val botsArr = root.optJSONArray("bots") ?: JSONArray()
                            val bots = (0 until botsArr.length()).map { parseBot(botsArr.getJSONObject(it)) }
                            return@withContext parseCompany(c).copy(bots = bots, botsCount = bots.size)
                        }
                    }
            } catch (_: Exception) {
            }
        }
        deployLocalCompany(description, proposal)
    }

    suspend fun sendFounderMessage(companyId: String, text: String, targetBotId: String? = null) = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                val body = JSONObject().put("text", text)
                if (targetBotId != null) body.put("target_bot_id", targetBotId)
                client.newCall(reqBuilder("/api/companies/$companyId/chat").post(body.toString().toRequestBody(jsonMedia)).build())
                    .execute().use { resp ->
                        if (resp.isSuccessful) {
                            val json = JSONObject(resp.body?.string() ?: "{}")
                            val mission = json.optJSONObject("mission")
                            val apr = mission?.optJSONObject("approval")
                            if (apr != null) {
                                ApprovalNotifier.showApprovalNotification(context, parseApproval(apr))
                            }
                            return@withContext
                        }
                    }
            } catch (_: Exception) {
            }
        }
        handleLocalMessage(companyId, text)
    }

    suspend fun requestBoardMeeting(companyId: String) = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                client.newCall(reqBuilder("/api/companies/$companyId/board").post("{}".toRequestBody(jsonMedia)).build())
                    .execute().use { if (it.isSuccessful) return@withContext }
            } catch (_: Exception) {
            }
        }
        val comp = localCompanies.firstOrNull { it.id == companyId } ?: return@withContext
        val shipped = localApprovals.count { it.companyId == companyId && it.status == "EXECUTED" }
        val arts = localArtifacts[companyId]?.size ?: 0
        val board = BoardMeetingData(
            title = "Board meeting · ${comp.name}",
            sourcesCount = arts + comp.bots.size,
            shipped = shipped,
            missions = arts,
            qaPassed = arts,
            decisionsApproved = localApprovals.count { it.companyId == companyId && it.status != "PENDING" },
        )
        localMessages.getOrPut(companyId) { mutableListOf() }.add(
            ChatMessageItem(
                id = "msg_${UUID.randomUUID().toString().take(8)}",
                companyId = companyId,
                senderType = "bot",
                botName = "CEO",
                content = "Ready for the board.",
                cardType = "board_meeting",
                boardMeeting = board,
            )
        )
    }

    suspend fun decideApproval(approvalId: String, decision: String, feedback: String = ""): ApprovalItem? =
        withContext(Dispatchers.IO) {
            if (isLiveConnected) {
                try {
                    val body = JSONObject()
                        .put("decision", decision)
                        .put("device", "Android")
                        .put("feedback", feedback)
                        .toString()
                    client.newCall(reqBuilder("/api/approvals/$approvalId/decide").post(body.toRequestBody(jsonMedia)).build())
                        .execute().use { resp ->
                            if (resp.isSuccessful) {
                                return@withContext parseApproval(JSONObject(resp.body?.string() ?: "{}").getJSONObject("approval"))
                            }
                        }
                } catch (_: Exception) {
                }
            }
            val idx = localApprovals.indexOfFirst { it.id == approvalId }
            if (idx == -1) return@withContext null
            val existing = localApprovals[idx]
            val nextStatus = if (decision.uppercase() == "APPROVED") "EXECUTED" else "REJECTED"
            val updated = existing.copy(
                status = nextStatus,
                decidedByDevice = "Android",
                feedback = feedback,
                completionNote = if (nextStatus == "EXECUTED") "Shipped. Team & memory updated." else "Sent back for revision.",
            )
            localApprovals[idx] = updated
            val msgs = localMessages[existing.companyId]
            if (msgs != null) {
                for (i in msgs.indices) {
                    if (msgs[i].cardType == "approval" && msgs[i].approval?.id == approvalId) {
                        msgs[i] = msgs[i].copy(approval = updated)
                    }
                }
                if (nextStatus == "EXECUTED") {
                    msgs.add(
                        ChatMessageItem(
                            id = "msg_${UUID.randomUUID().toString().take(8)}",
                            companyId = existing.companyId,
                            senderType = "system",
                            botName = "CEO",
                            content = "You approved ${existing.title} on Android",
                            cardType = "approval_confirmed",
                        )
                    )
                }
            }
            updated
        }

    suspend fun fetchArtifacts(companyId: String? = null): List<ArtifactItem> = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                val query = if (companyId != null) "?company_id=$companyId" else ""
                client.newCall(reqBuilder("/api/artifacts$query").get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONObject(resp.body?.string() ?: "{}").optJSONArray("artifacts") ?: JSONArray()
                        return@withContext (0 until arr.length()).map { parseArtifact(arr.getJSONObject(it)) }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (companyId != null) localArtifacts[companyId]?.toList() ?: emptyList()
        else localArtifacts.values.flatten()
    }

    suspend fun downloadArtifact(artifact: ArtifactItem): File? = withContext(Dispatchers.IO) {
        if (!isLiveConnected) return@withContext null
        try {
            client.newCall(reqBuilder("/api/artifacts/${artifact.id}/download").get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                val outFile = File(dir, artifact.filename)
                outFile.writeBytes(resp.body?.bytes() ?: return@withContext null)
                outFile
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun addConnector(companyId: String, name: String, baseUrl: String, authValue: String, live: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (isLiveConnected) {
                try {
                    val body = JSONObject()
                        .put("name", name)
                        .put("base_url", baseUrl)
                        .put("auth_header", "Authorization")
                        .put("auth_value", authValue)
                        .put("live_enabled", live)
                        .toString()
                    client.newCall(reqBuilder("/api/companies/$companyId/connectors").post(body.toRequestBody(jsonMedia)).build())
                        .execute().use { return@withContext it.isSuccessful }
                } catch (_: Exception) {
                }
            }
            localConnectors.getOrPut(companyId) { mutableListOf() }
                .add(ConnectorItem("con_${UUID.randomUUID().toString().take(6)}", name, baseUrl, live))
            true
        }

    suspend fun fetchSettings(): Triple<List<ProviderStatus>, Boolean, String> = withContext(Dispatchers.IO) {
        if (isLiveConnected) {
            try {
                client.newCall(reqBuilder("/api/settings").get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string() ?: "{}")
                        val provObj = root.optJSONObject("llm_providers") ?: JSONObject()
                        val list = mutableListOf<ProviderStatus>()
                        val keys = provObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val p = provObj.getJSONObject(k)
                            list.add(ProviderStatus(k, p.optBoolean("configured"), p.optString("model")))
                        }
                        return@withContext Triple(list, root.optBoolean("kill_switch"), root.optString("ntfy_topic"))
                    }
                }
            } catch (_: Exception) {
            }
        }
        Triple(
            listOf(
                ProviderStatus("openrouter", false, "anthropic/claude-3.5-sonnet"),
                ProviderStatus("gemini", false, "gemini-2.5-flash"),
                ProviderStatus("xai", false, "grok-3-mini"),
                ProviderStatus("openai", false, "gpt-4o-mini"),
                ProviderStatus("anthropic", false, "claude-3-5-sonnet-latest"),
            ),
            false,
            "",
        )
    }

    suspend fun saveProviderKey(provider: String, apiKey: String, model: String): Boolean = withContext(Dispatchers.IO) {
        if (!isLiveConnected) return@withContext false
        try {
            val payload = JSONObject().put(
                "llm_providers",
                JSONObject().put(provider, JSONObject().put("api_key", apiKey).put("model", model))
            ).toString()
            client.newCall(reqBuilder("/api/settings").put(payload.toRequestBody(jsonMedia)).build()).execute().use {
                it.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun toggleKillSwitch(active: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!isLiveConnected) return@withContext active
        try {
            client.newCall(reqBuilder("/api/kill-switch?active=$active").post("{}".toRequestBody(jsonMedia)).build())
                .execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // --- Local Dynamic Fallback (Zero Hardcoded Verticals) ---

    private fun synthesizeLocalProposal(description: String): CompanyProposalData {
        val clean = description.trim()
        val words = clean.split(Regex("\\s+")).map { it.trim('.', ',', '!', '?', ';', ':') }.filter { it.length > 3 }
        val name = if (words.isNotEmpty()) words.take(3).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.take(34) else "New Venture"
        val cid = "preview"
        val bots = listOf(
            BotItem("b0", cid, "CEO", "Chief Executive", "Sets goals, hands out work", "circle", "#111111", isCeo = true),
            BotItem("b1", cid, "Strategist", "Research & Specs", "Turns goals into specs", "triangle", "#EC4899"),
            BotItem("b2", cid, "Builder", "Core Production", "Builds in isolated sandbox", "hexagon", "#F97316"),
            BotItem("b3", cid, "Quality Auditor", "Independent QA", "Verifies every deliverable", "semicircle", "#2563EB", isQa = true),
            BotItem("b4", cid, "Publisher", "Launch & Delivery", "Prepares store / release", "pill", "#10B981"),
            BotItem("b5", cid, "Memory", "Shared Knowledge", "Keeps everyone on the same page", "squircle", "#7C3AED"),
        )
        return CompanyProposalData(
            name = name,
            missionStatement = clean.take(180),
            strategySummary = "CEO dynamically spawns a dedicated sandbox for $name with independent QA and human approval before anything ships.",
            recommendedConnectors = listOf("GitHub / Store API", "E2B Cloud Sandbox", "Webhook / Export Kit"),
            bots = bots,
            rawProposalJson = "{}",
        )
    }

    private fun deployLocalCompany(description: String, proposal: CompanyProposalData): CompanyItem {
        val cid = "cmp_${UUID.randomUUID().toString().take(8)}"
        val bots = proposal.bots.mapIndexed { i, b -> b.copy(id = "bot_${cid}_$i", companyId = cid, statusText = b.tagline) }
        val comp = CompanyItem(
            id = cid,
            name = proposal.name,
            description = description,
            missionStatement = proposal.missionStatement,
            botsCount = bots.size,
            bots = bots,
        )
        localCompanies.add(0, comp)
        localMessages[cid] = mutableListOf(
            ChatMessageItem("m1_$cid", cid, "human", content = description),
            ChatMessageItem("m2_$cid", cid, "bot", botName = "CEO", content = "Hiring your company: ${bots.size} bots and one human, you. Nothing ships without your yes."),
            ChatMessageItem(
                id = "m3_$cid",
                companyId = cid,
                senderType = "bot",
                botName = "CEO",
                cardType = "company_org",
                orgCompanyName = proposal.name,
                orgSubtitle = "1 human · ${bots.size} bots · isolated sandbox",
                orgConnectors = proposal.recommendedConnectors,
                orgBots = bots,
            ),
        )
        return comp
    }

    private fun handleLocalMessage(companyId: String, text: String) {
        val comp = localCompanies.firstOrNull { it.id == companyId } ?: return
        val msgs = localMessages.getOrPut(companyId) { mutableListOf() }
        msgs.add(ChatMessageItem("mh_${UUID.randomUUID().toString().take(6)}", companyId, "human", content = text))

        val producer = comp.bots.firstOrNull { !it.isCeo && !it.isQa } ?: comp.bots.first()
        val qa = comp.bots.firstOrNull { it.isQa } ?: comp.bots.last()

        val artId = "art_${UUID.randomUUID().toString().take(8)}"
        val slug = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(28).ifBlank { "deliverable" }
        val art = ArtifactItem(
            id = artId,
            companyId = companyId,
            botName = producer.name,
            title = text.take(56),
            filename = "$slug.pdf",
            relPath = "output/$slug.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42_840,
            summary = "Generated inside ${comp.name} sandbox by ${producer.name}",
            qaStatus = "PASSED",
            qaScore = 95,
            qaReport = "All checks passed in isolated sandbox (${qa.name})",
        )
        localArtifacts.getOrPut(companyId) { mutableListOf() }.add(0, art)
        msgs.add(
            ChatMessageItem(
                id = "ma_${UUID.randomUUID().toString().take(6)}",
                companyId = companyId,
                senderType = "bot",
                botName = producer.name,
                content = "${producer.name} prepared deliverable",
                cardType = "artifact",
                artifact = art,
            )
        )

        val apr = ApprovalItem(
            id = "apr_${UUID.randomUUID().toString().take(8)}",
            companyId = companyId,
            artifactId = artId,
            title = "Ship ${art.title}?",
            subtitle = "QA 95/100 by ${qa.name} · Verified in sandbox",
            actionType = "SHIP_RELEASE",
            status = "PENDING",
        )
        localApprovals.add(0, apr)
        msgs.add(
            ChatMessageItem(
                id = "map_${UUID.randomUUID().toString().take(6)}",
                companyId = companyId,
                senderType = "bot",
                botName = "CEO",
                content = "${art.title} is ready. Shipping it to users needs your yes.",
                cardType = "approval",
                approval = apr,
            )
        )
        ApprovalNotifier.showApprovalNotification(context, apr)
    }

    // --- JSON Parsers ---

    private fun parseCompany(o: JSONObject): CompanyItem {
        val botsArr = o.optJSONArray("bots") ?: JSONArray()
        val bots = (0 until botsArr.length()).map { parseBot(botsArr.getJSONObject(it)) }
        return CompanyItem(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            missionStatement = o.optString("mission_statement"),
            status = o.optString("status", "ACTIVE"),
            botsCount = o.optInt("bots_count", bots.size),
            pendingApprovals = o.optInt("pending_approvals", 0),
            artifactsCount = o.optInt("artifacts_count", 0),
            tokensUsedToday = o.optInt("tokens_used_today", 0),
            dailyTokenBudget = o.optInt("daily_token_budget", 250000),
            bots = bots,
        )
    }

    private fun parseBot(o: JSONObject): BotItem {
        val toolsArr = o.optJSONArray("allowed_tools") ?: JSONArray()
        val tools = (0 until toolsArr.length()).map { toolsArr.optString(it) }
        return BotItem(
            id = o.optString("id"),
            companyId = o.optString("company_id"),
            name = o.optString("name"),
            role = o.optString("role"),
            tagline = o.optString("tagline"),
            avatarShape = o.optString("avatar_shape", "circle"),
            avatarColor = o.optString("avatar_color", "#111111"),
            model = o.optString("model", "auto"),
            allowedTools = tools,
            isCeo = o.optBoolean("is_ceo", o.optInt("is_ceo", 0) == 1),
            isQa = o.optBoolean("is_qa", o.optInt("is_qa", 0) == 1),
            status = o.optString("status", "idle"),
            statusText = o.optString("status_text", o.optString("tagline")),
            unreadCount = o.optInt("unread_count", 0),
        )
    }

    private fun parseArtifact(o: JSONObject): ArtifactItem = ArtifactItem(
        id = o.optString("id"),
        companyId = o.optString("company_id"),
        botName = o.optString("bot_name"),
        title = o.optString("title"),
        filename = o.optString("filename"),
        relPath = o.optString("rel_path"),
        mimeType = o.optString("mime_type"),
        sizeBytes = o.optLong("size_bytes"),
        summary = o.optString("summary"),
        qaStatus = o.optString("qa_status", "PENDING"),
        qaScore = if (o.isNull("qa_score")) null else o.optInt("qa_score"),
        qaReport = o.optString("qa_report"),
        createdAt = o.optString("created_at"),
    )

    private fun parseApproval(o: JSONObject): ApprovalItem {
        val resObj = o.optJSONObject("execution_result")
        return ApprovalItem(
            id = o.optString("id"),
            companyId = o.optString("company_id"),
            artifactId = o.optString("artifact_id").ifBlank { null },
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            actionType = o.optString("action_type"),
            status = o.optString("status", "PENDING"),
            decidedByDevice = o.optString("decided_by_device").ifBlank { null },
            feedback = o.optString("feedback"),
            completionNote = resObj?.optString("note") ?: "",
            createdAt = o.optString("created_at"),
        )
    }

    private fun parseProposal(o: JSONObject): CompanyProposalData {
        val botsArr = o.optJSONArray("bots") ?: JSONArray()
        val connArr = o.optJSONArray("recommended_connectors") ?: JSONArray()
        return CompanyProposalData(
            name = o.optString("name"),
            missionStatement = o.optString("mission_statement"),
            strategySummary = o.optString("strategy_summary"),
            recommendedConnectors = (0 until connArr.length()).map { connArr.optString(it) },
            bots = (0 until botsArr.length()).map { parseBot(botsArr.getJSONObject(it)) },
            rawProposalJson = o.toString(),
        )
    }

    private fun parseMessage(o: JSONObject): ChatMessageItem {
        val cardType = o.optString("card_type").ifBlank { null }
        val payload = o.optJSONObject("card_payload")
        var orgBots: List<BotItem> = emptyList()
        var orgSub = ""
        var orgName = ""
        var orgConns: List<String> = emptyList()
        var art: ArtifactItem? = null
        var apr: ApprovalItem? = null
        var board: BoardMeetingData? = null

        if (payload != null) {
            when (cardType) {
                "company_org" -> {
                    orgName = payload.optString("company_name")
                    orgSub = payload.optString("subtitle")
                    val bArr = payload.optJSONArray("bots") ?: JSONArray()
                    orgBots = (0 until bArr.length()).map { parseBot(bArr.getJSONObject(it)) }
                    val cArr = payload.optJSONArray("recommended_connectors") ?: JSONArray()
                    orgConns = (0 until cArr.length()).map { cArr.optString(it) }
                }
                "artifact" -> art = parseArtifact(payload)
                "approval" -> apr = parseApproval(payload)
                "board_meeting" -> {
                    val m = payload.optJSONObject("metrics") ?: JSONObject()
                    board = BoardMeetingData(
                        title = payload.optString("title", "Board meeting"),
                        sourcesCount = payload.optInt("sources_count", 0),
                        shipped = m.optInt("shipped", 0),
                        missions = m.optInt("missions", 0),
                        qaPassed = m.optInt("qa_passed", 0),
                        decisionsApproved = m.optInt("decisions_approved", 0),
                        tokensUsed = payload.optInt("tokens_used", 0),
                        tokenBudget = payload.optInt("token_budget", 250000),
                    )
                }
            }
        }

        return ChatMessageItem(
            id = o.optString("id"),
            companyId = o.optString("company_id"),
            senderType = o.optString("sender_type", "bot"),
            botId = o.optString("bot_id").ifBlank { null },
            botName = o.optString("bot_name"),
            content = o.optString("content"),
            cardType = cardType,
            orgBots = orgBots,
            orgSubtitle = orgSub,
            orgCompanyName = orgName,
            orgConnectors = orgConns,
            artifact = art,
            approval = apr,
            boardMeeting = board,
            createdAt = o.optString("created_at"),
        )
    }
}
