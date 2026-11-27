package com.foundryos.app.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.BoardMeetingData
import com.foundryos.app.data.BotItem
import com.foundryos.app.data.ChatMessageItem
import com.foundryos.app.data.CompanyItem
import com.foundryos.app.data.ConnectorItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "ceo_ondevice.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS companies (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                mission_statement TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                daily_token_budget INTEGER NOT NULL DEFAULT 250000,
                tokens_used_today INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bots (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                role TEXT NOT NULL,
                tagline TEXT NOT NULL,
                system_prompt TEXT NOT NULL,
                avatar_shape TEXT NOT NULL,
                avatar_color TEXT NOT NULL,
                model TEXT NOT NULL DEFAULT 'auto',
                allowed_tools_json TEXT NOT NULL DEFAULT '[]',
                is_ceo INTEGER NOT NULL DEFAULT 0,
                is_qa INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'idle',
                status_text TEXT NOT NULL DEFAULT 'Ready',
                unread_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                sender_type TEXT NOT NULL,
                bot_id TEXT,
                bot_name TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL,
                card_type TEXT,
                card_payload_json TEXT,
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS missions (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                goal TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'RUNNING',
                qa_score INTEGER,
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artifacts (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                bot_name TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                filename TEXT NOT NULL,
                rel_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                summary TEXT NOT NULL DEFAULT '',
                qa_status TEXT NOT NULL DEFAULT 'PENDING',
                qa_score INTEGER,
                qa_report TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS approvals (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                mission_id TEXT,
                artifact_id TEXT,
                title TEXT NOT NULL,
                subtitle TEXT NOT NULL DEFAULT '',
                action_type TEXT NOT NULL,
                action_payload_json TEXT NOT NULL DEFAULT '{}',
                status TEXT NOT NULL DEFAULT 'PENDING',
                decided_by_device TEXT,
                feedback TEXT NOT NULL DEFAULT '',
                completion_note TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS connectors (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                name TEXT NOT NULL,
                base_url TEXT NOT NULL,
                auth_header TEXT NOT NULL DEFAULT 'Authorization',
                auth_value TEXT NOT NULL DEFAULT '',
                live_enabled INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memory_entries (
                id TEXT PRIMARY KEY,
                company_id TEXT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
                bot_name TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun insertCompany(c: CompanyItem) {
        val cv = ContentValues().apply {
            put("id", c.id)
            put("name", c.name)
            put("description", c.description)
            put("mission_statement", c.missionStatement)
            put("status", c.status)
            put("daily_token_budget", c.dailyTokenBudget)
            put("tokens_used_today", c.tokensUsedToday)
            put("created_at", nowIso())
        }
        writableDatabase.insertOrThrow("companies", null, cv)
    }

    fun deleteCompany(companyId: String) {
        writableDatabase.delete("companies", "id = ?", arrayOf(companyId))
    }

    fun addTokensUsed(companyId: String, tokens: Int) {
        if (tokens <= 0) return
        writableDatabase.execSQL(
            "UPDATE companies SET tokens_used_today = tokens_used_today + ? WHERE id = ?",
            arrayOf(tokens, companyId),
        )
    }

    fun insertBot(b: BotItem) {
        val cv = ContentValues().apply {
            put("id", b.id)
            put("company_id", b.companyId)
            put("name", b.name)
            put("role", b.role)
            put("tagline", b.tagline)
            put("system_prompt", b.systemPrompt)
            put("avatar_shape", b.avatarShape)
            put("avatar_color", b.avatarColor)
            put("model", b.model)
            put("allowed_tools_json", JSONArray(b.allowedTools).toString())
            put("is_ceo", if (b.isCeo) 1 else 0)
            put("is_qa", if (b.isQa) 1 else 0)
            put("status", b.status)
            put("status_text", b.statusText)
            put("unread_count", b.unreadCount)
            put("created_at", nowIso())
        }
        writableDatabase.insertOrThrow("bots", null, cv)
    }

    fun updateBotStatus(botId: String, status: String, statusText: String, incrementUnread: Boolean = false) {
        if (incrementUnread) {
            writableDatabase.execSQL(
                "UPDATE bots SET status = ?, status_text = ?, unread_count = unread_count + 1 WHERE id = ?",
                arrayOf(status, statusText.take(80), botId),
            )
        } else {
            writableDatabase.execSQL(
                "UPDATE bots SET status = ?, status_text = ? WHERE id = ?",
                arrayOf(status, statusText.take(80), botId),
            )
        }
    }

    fun updateBotModel(botId: String, model: String) {
        writableDatabase.execSQL("UPDATE bots SET model = ? WHERE id = ?", arrayOf(model, botId))
    }

    fun clearBotUnread(botId: String) {
        writableDatabase.execSQL("UPDATE bots SET unread_count = 0 WHERE id = ?", arrayOf(botId))
    }

    fun listCompanies(): List<CompanyItem> {
        val out = mutableListOf<CompanyItem>()
        readableDatabase.rawQuery("SELECT * FROM companies ORDER BY created_at DESC", null).use { cur ->
            while (cur.moveToNext()) {
                val cid = cur.getString(cur.getColumnIndexOrThrow("id"))
                val bots = listBots(cid)
                val pending = scalarInt("SELECT COUNT(*) FROM approvals WHERE company_id = ? AND status = 'PENDING'", cid)
                val arts = scalarInt("SELECT COUNT(*) FROM artifacts WHERE company_id = ?", cid)
                out.add(
                    CompanyItem(
                        id = cid,
                        name = cur.getString(cur.getColumnIndexOrThrow("name")),
                        description = cur.getString(cur.getColumnIndexOrThrow("description")),
                        missionStatement = cur.getString(cur.getColumnIndexOrThrow("mission_statement")),
                        status = cur.getString(cur.getColumnIndexOrThrow("status")),
                        botsCount = bots.size,
                        pendingApprovals = pending,
                        artifactsCount = arts,
                        tokensUsedToday = cur.getInt(cur.getColumnIndexOrThrow("tokens_used_today")),
                        dailyTokenBudget = cur.getInt(cur.getColumnIndexOrThrow("daily_token_budget")),
                        bots = bots,
                    )
                )
            }
        }
        return out
    }

    fun getCompany(companyId: String): CompanyItem? = listCompanies().firstOrNull { it.id == companyId }

    fun listBots(companyId: String): List<BotItem> {
        val out = mutableListOf<BotItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM bots WHERE company_id = ? ORDER BY is_ceo DESC, created_at ASC",
            arrayOf(companyId),
        ).use { cur ->
            while (cur.moveToNext()) {
                val toolsJson = JSONArray(cur.getString(cur.getColumnIndexOrThrow("allowed_tools_json")))
                val tools = (0 until toolsJson.length()).map { toolsJson.optString(it) }
                out.add(
                    BotItem(
                        id = cur.getString(cur.getColumnIndexOrThrow("id")),
                        companyId = cur.getString(cur.getColumnIndexOrThrow("company_id")),
                        name = cur.getString(cur.getColumnIndexOrThrow("name")),
                        role = cur.getString(cur.getColumnIndexOrThrow("role")),
                        tagline = cur.getString(cur.getColumnIndexOrThrow("tagline")),
                        systemPrompt = cur.getString(cur.getColumnIndexOrThrow("system_prompt")),
                        avatarShape = cur.getString(cur.getColumnIndexOrThrow("avatar_shape")),
                        avatarColor = cur.getString(cur.getColumnIndexOrThrow("avatar_color")),
                        model = cur.getString(cur.getColumnIndexOrThrow("model")),
                        allowedTools = tools,
                        isCeo = cur.getInt(cur.getColumnIndexOrThrow("is_ceo")) == 1,
                        isQa = cur.getInt(cur.getColumnIndexOrThrow("is_qa")) == 1,
                        status = cur.getString(cur.getColumnIndexOrThrow("status")),
                        statusText = cur.getString(cur.getColumnIndexOrThrow("status_text")),
                        unreadCount = cur.getInt(cur.getColumnIndexOrThrow("unread_count")),
                    )
                )
            }
        }
        return out
    }

    fun insertMessage(m: ChatMessageItem, rawPayloadJson: String? = null) {
        val cv = ContentValues().apply {
            put("id", m.id)
            put("company_id", m.companyId)
            put("sender_type", m.senderType)
            put("bot_id", m.botId)
            put("bot_name", m.botName)
            put("content", m.content)
            put("card_type", m.cardType)
            put("card_payload_json", rawPayloadJson)
            put("created_at", m.createdAt.ifBlank { nowIso() })
        }
        writableDatabase.insertOrThrow("messages", null, cv)
    }

    fun listMessages(companyId: String): List<ChatMessageItem> {
        val out = mutableListOf<ChatMessageItem>()
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE company_id = ? ORDER BY created_at ASC LIMIT 200",
            arrayOf(companyId),
        ).use { cur ->
            while (cur.moveToNext()) {
                val cardType = cur.getString(cur.getColumnIndexOrThrow("card_type"))
                val payloadStr = cur.getString(cur.getColumnIndexOrThrow("card_payload_json"))
                val payload = if (!payloadStr.isNullOrBlank()) {
                    try { JSONObject(payloadStr) } catch (_: Exception) { null }
                } else null

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
                            val cArr = payload.optJSONArray("recommended_connectors") ?: JSONArray()
                            orgConns = (0 until cArr.length()).map { cArr.optString(it) }
                            val bArr = payload.optJSONArray("bots") ?: JSONArray()
                            orgBots = (0 until bArr.length()).map {
                                val b = bArr.getJSONObject(it)
                                val tArr = b.optJSONArray("allowed_tools") ?: JSONArray()
                                BotItem(
                                    id = b.optString("id"),
                                    companyId = companyId,
                                    name = b.optString("name"),
                                    role = b.optString("role"),
                                    tagline = b.optString("tagline"),
                                    avatarShape = b.optString("avatar_shape", "circle"),
                                    avatarColor = b.optString("avatar_color", "#111111"),
                                    allowedTools = (0 until tArr.length()).map { i -> tArr.optString(i) },
                                    isCeo = b.optBoolean("is_ceo", false),
                                    isQa = b.optBoolean("is_qa", false),
                                )
                            }
                        }
                        "artifact" -> {
                            val artId = payload.optString("id")
                            art = getArtifact(artId) ?: ArtifactItem(
                                id = artId,
                                companyId = companyId,
                                botName = payload.optString("bot_name"),
                                title = payload.optString("title"),
                                filename = payload.optString("filename"),
                                relPath = payload.optString("rel_path"),
                                mimeType = payload.optString("mime_type"),
                                sizeBytes = payload.optLong("size_bytes"),
                                summary = payload.optString("summary"),
                                qaStatus = payload.optString("qa_status"),
                                qaScore = if (payload.isNull("qa_score")) null else payload.optInt("qa_score"),
                                qaReport = payload.optString("qa_report"),
                            )
                        }
                        "approval" -> {
                            val aprId = payload.optString("id")
                            apr = getApproval(aprId)
                        }
                        "board_meeting" -> {
                            board = BoardMeetingData(
                                title = payload.optString("title"),
                                sourcesCount = payload.optInt("sources_count"),
                                shipped = payload.optInt("shipped"),
                                missions = payload.optInt("missions"),
                                qaPassed = payload.optInt("qa_passed"),
                                decisionsApproved = payload.optInt("decisions_approved"),
                                tokensUsed = payload.optInt("tokens_used"),
                                tokenBudget = payload.optInt("token_budget", 250000),
                            )
                        }
                    }
                }

                out.add(
                    ChatMessageItem(
                        id = cur.getString(cur.getColumnIndexOrThrow("id")),
                        companyId = companyId,
                        senderType = cur.getString(cur.getColumnIndexOrThrow("sender_type")),
                        botId = cur.getString(cur.getColumnIndexOrThrow("bot_id")),
                        botName = cur.getString(cur.getColumnIndexOrThrow("bot_name")),
                        content = cur.getString(cur.getColumnIndexOrThrow("content")),
                        cardType = cardType,
                        orgBots = orgBots,
                        orgSubtitle = orgSub,
                        orgCompanyName = orgName,
                        orgConnectors = orgConns,
                        artifact = art,
                        approval = apr,
                        boardMeeting = board,
                        createdAt = cur.getString(cur.getColumnIndexOrThrow("created_at")),
                    )
                )
            }
        }
        return out
    }

    fun insertMission(id: String, companyId: String, title: String, goal: String) {
        val cv = ContentValues().apply {
            put("id", id)
            put("company_id", companyId)
            put("title", title)
            put("goal", goal)
            put("status", "RUNNING")
            put("created_at", nowIso())
        }
        writableDatabase.insertOrThrow("missions", null, cv)
    }

    fun updateMissionStatus(missionId: String, status: String, qaScore: Int? = null) {
        if (qaScore != null) {
            writableDatabase.execSQL(
                "UPDATE missions SET status = ?, qa_score = ? WHERE id = ?",
                arrayOf(status, qaScore, missionId),
            )
        } else {
            writableDatabase.execSQL("UPDATE missions SET status = ? WHERE id = ?", arrayOf(status, missionId))
        }
    }

    fun insertArtifact(a: ArtifactItem) {
        val cv = ContentValues().apply {
            put("id", a.id)
            put("company_id", a.companyId)
            put("bot_name", a.botName)
            put("title", a.title)
            put("filename", a.filename)
            put("rel_path", a.relPath)
            put("mime_type", a.mimeType)
            put("size_bytes", a.sizeBytes)
            put("summary", a.summary)
            put("qa_status", a.qaStatus)
            if (a.qaScore != null) put("qa_score", a.qaScore)
            put("qa_report", a.qaReport)
            put("created_at", a.createdAt.ifBlank { nowIso() })
        }
        writableDatabase.insertOrThrow("artifacts", null, cv)
    }

    fun updateArtifactQa(artifactId: String, qaStatus: String, qaScore: Int, qaReport: String) {
        writableDatabase.execSQL(
            "UPDATE artifacts SET qa_status = ?, qa_score = ?, qa_report = ? WHERE id = ?",
            arrayOf(qaStatus, qaScore, qaReport, artifactId),
        )
    }

    fun getArtifact(artifactId: String): ArtifactItem? {
        readableDatabase.rawQuery("SELECT * FROM artifacts WHERE id = ?", arrayOf(artifactId)).use { cur ->
            if (!cur.moveToNext()) return null
            val qIdx = cur.getColumnIndexOrThrow("qa_score")
            return ArtifactItem(
                id = cur.getString(cur.getColumnIndexOrThrow("id")),
                companyId = cur.getString(cur.getColumnIndexOrThrow("company_id")),
                botName = cur.getString(cur.getColumnIndexOrThrow("bot_name")),
                title = cur.getString(cur.getColumnIndexOrThrow("title")),
                filename = cur.getString(cur.getColumnIndexOrThrow("filename")),
                relPath = cur.getString(cur.getColumnIndexOrThrow("rel_path")),
                mimeType = cur.getString(cur.getColumnIndexOrThrow("mime_type")),
                sizeBytes = cur.getLong(cur.getColumnIndexOrThrow("size_bytes")),
                summary = cur.getString(cur.getColumnIndexOrThrow("summary")),
                qaStatus = cur.getString(cur.getColumnIndexOrThrow("qa_status")),
                qaScore = if (cur.isNull(qIdx)) null else cur.getInt(qIdx),
                qaReport = cur.getString(cur.getColumnIndexOrThrow("qa_report")),
                createdAt = cur.getString(cur.getColumnIndexOrThrow("created_at")),
            )
        }
    }

    fun listArtifacts(companyId: String? = null): List<ArtifactItem> {
        val out = mutableListOf<ArtifactItem>()
        val sql = if (companyId != null) {
            "SELECT * FROM artifacts WHERE company_id = ? ORDER BY created_at DESC"
        } else {
            "SELECT * FROM artifacts ORDER BY created_at DESC LIMIT 100"
        }
        val args = if (companyId != null) arrayOf(companyId) else null
        readableDatabase.rawQuery(sql, args).use { cur ->
            while (cur.moveToNext()) {
                val qIdx = cur.getColumnIndexOrThrow("qa_score")
                out.add(
                    ArtifactItem(
                        id = cur.getString(cur.getColumnIndexOrThrow("id")),
                        companyId = cur.getString(cur.getColumnIndexOrThrow("company_id")),
                        botName = cur.getString(cur.getColumnIndexOrThrow("bot_name")),
                        title = cur.getString(cur.getColumnIndexOrThrow("title")),
                        filename = cur.getString(cur.getColumnIndexOrThrow("filename")),
                        relPath = cur.getString(cur.getColumnIndexOrThrow("rel_path")),
                        mimeType = cur.getString(cur.getColumnIndexOrThrow("mime_type")),
                        sizeBytes = cur.getLong(cur.getColumnIndexOrThrow("size_bytes")),
                        summary = cur.getString(cur.getColumnIndexOrThrow("summary")),
                        qaStatus = cur.getString(cur.getColumnIndexOrThrow("qa_status")),
                        qaScore = if (cur.isNull(qIdx)) null else cur.getInt(qIdx),
                        qaReport = cur.getString(cur.getColumnIndexOrThrow("qa_report")),
                        createdAt = cur.getString(cur.getColumnIndexOrThrow("created_at")),
                    )
                )
            }
        }
        return out
    }

    fun insertApproval(a: ApprovalItem) {
        val cv = ContentValues().apply {
            put("id", a.id)
            put("company_id", a.companyId)
            put("mission_id", a.missionId)
            put("artifact_id", a.artifactId)
            put("title", a.title)
            put("subtitle", a.subtitle)
            put("action_type", a.actionType)
            put("action_payload_json", a.actionPayloadJson)
            put("status", a.status)
            put("created_at", a.createdAt.ifBlank { nowIso() })
        }
        writableDatabase.insertOrThrow("approvals", null, cv)
    }

    fun getApproval(approvalId: String): ApprovalItem? {
        readableDatabase.rawQuery("SELECT * FROM approvals WHERE id = ?", arrayOf(approvalId)).use { cur ->
            if (!cur.moveToNext()) return null
            return ApprovalItem(
                id = cur.getString(cur.getColumnIndexOrThrow("id")),
                companyId = cur.getString(cur.getColumnIndexOrThrow("company_id")),
                missionId = cur.getString(cur.getColumnIndexOrThrow("mission_id")),
                artifactId = cur.getString(cur.getColumnIndexOrThrow("artifact_id")),
                title = cur.getString(cur.getColumnIndexOrThrow("title")),
                subtitle = cur.getString(cur.getColumnIndexOrThrow("subtitle")),
                actionType = cur.getString(cur.getColumnIndexOrThrow("action_type")),
                actionPayloadJson = cur.getString(cur.getColumnIndexOrThrow("action_payload_json")),
                status = cur.getString(cur.getColumnIndexOrThrow("status")),
                decidedByDevice = cur.getString(cur.getColumnIndexOrThrow("decided_by_device")),
                feedback = cur.getString(cur.getColumnIndexOrThrow("feedback")),
                completionNote = cur.getString(cur.getColumnIndexOrThrow("completion_note")),
                createdAt = cur.getString(cur.getColumnIndexOrThrow("created_at")),
            )
        }
    }

    /** Atomic optimistic claim: returns true ONLY for the first caller transitioning PENDING -> nextStatus. */
    fun claimApprovalDecision(approvalId: String, nextStatus: String, device: String, feedback: String): Boolean {
        val cv = ContentValues().apply {
            put("status", nextStatus)
            put("decided_by_device", device)
            put("feedback", feedback)
        }
        val rows = writableDatabase.update("approvals", cv, "id = ? AND status = 'PENDING'", arrayOf(approvalId))
        return rows == 1
    }

    fun finalizeApprovalExecution(approvalId: String, finalStatus: String, note: String) {
        writableDatabase.execSQL(
            "UPDATE approvals SET status = ?, completion_note = ? WHERE id = ?",
            arrayOf(finalStatus, note, approvalId),
        )
    }

    fun insertConnector(companyId: String, id: String, name: String, baseUrl: String, authHeader: String, authValue: String, live: Boolean) {
        val cv = ContentValues().apply {
            put("id", id)
            put("company_id", companyId)
            put("name", name)
            put("base_url", baseUrl)
            put("auth_header", authHeader)
            put("auth_value", authValue)
            put("live_enabled", if (live) 1 else 0)
            put("created_at", nowIso())
        }
        writableDatabase.insertOrThrow("connectors", null, cv)
    }

    fun listConnectors(companyId: String): List<ConnectorItem> {
        val out = mutableListOf<ConnectorItem>()
        readableDatabase.rawQuery("SELECT * FROM connectors WHERE company_id = ? ORDER BY created_at DESC", arrayOf(companyId)).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    ConnectorItem(
                        id = cur.getString(cur.getColumnIndexOrThrow("id")),
                        name = cur.getString(cur.getColumnIndexOrThrow("name")),
                        baseUrl = cur.getString(cur.getColumnIndexOrThrow("base_url")),
                        authHeader = cur.getString(cur.getColumnIndexOrThrow("auth_header")),
                        authValue = cur.getString(cur.getColumnIndexOrThrow("auth_value")),
                        liveEnabled = cur.getInt(cur.getColumnIndexOrThrow("live_enabled")) == 1,
                    )
                )
            }
        }
        return out
    }

    fun rememberNote(id: String, companyId: String, botName: String, category: String, content: String) {
        val cv = ContentValues().apply {
            put("id", id)
            put("company_id", companyId)
            put("bot_name", botName)
            put("category", category)
            put("content", content.take(3000))
            put("created_at", nowIso())
        }
        writableDatabase.insertOrThrow("memory_entries", null, cv)
    }

    fun recallNotes(companyId: String, limit: Int = 8): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT bot_name, category, content FROM memory_entries WHERE company_id = ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(companyId, limit.toString()),
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add("(${cur.getString(1)} by ${cur.getString(0)}): ${cur.getString(2)}")
            }
        }
        return out
    }

    fun computeBoardMeeting(companyId: String): BoardMeetingData {
        val comp = getCompany(companyId)
        val shipped = scalarInt("SELECT COUNT(*) FROM approvals WHERE company_id = ? AND status = 'EXECUTED'", companyId)
        val missions = scalarInt("SELECT COUNT(*) FROM missions WHERE company_id = ?", companyId)
        val qaPassed = scalarInt("SELECT COUNT(*) FROM artifacts WHERE company_id = ? AND qa_status = 'PASSED'", companyId)
        val decisions = scalarInt("SELECT COUNT(*) FROM approvals WHERE company_id = ? AND status IN ('EXECUTED', 'APPROVED', 'REJECTED')", companyId)
        val memCount = scalarInt("SELECT COUNT(*) FROM memory_entries WHERE company_id = ?", companyId)
        return BoardMeetingData(
            title = "Board meeting · ${comp?.name ?: "Company"}",
            sourcesCount = memCount + missions,
            shipped = shipped,
            missions = missions,
            qaPassed = qaPassed,
            decisionsApproved = decisions,
            tokensUsed = comp?.tokensUsedToday ?: 0,
            tokenBudget = comp?.dailyTokenBudget ?: 250000,
        )
    }

    private fun scalarInt(sql: String, arg: String): Int {
        readableDatabase.rawQuery(sql, arrayOf(arg)).use { cur ->
            return if (cur.moveToFirst()) cur.getInt(0) else 0
        }
    }
}
