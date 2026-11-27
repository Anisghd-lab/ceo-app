package com.foundryos.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.foundryos.app.engine.AutonomousEngine
import com.foundryos.app.engine.LocalDatabase
import com.foundryos.app.engine.OnDeviceLLMClient
import com.foundryos.app.engine.OnDeviceSandbox
import java.io.File
import java.util.UUID

/**
 * Unified 100% On-Device Repository (Zero VPS / Zero External Server required).
 */
class FoundryRepository(private val context: Context) {
    private val db = LocalDatabase(context)
    private val sandbox = OnDeviceSandbox(context)
    private val llm = OnDeviceLLMClient(context)
    private val engine = AutonomousEngine(context, db, sandbox, llm)

    fun engineStatus(): Pair<Boolean, String> = llm.hasAnyKey() to llm.activeProviderSummary()

    fun fetchCompanies(): List<CompanyItem> = db.listCompanies()

    fun fetchCompanyDetail(companyId: String): Triple<List<BotItem>, List<ChatMessageItem>, List<ConnectorItem>> {
        return Triple(
            db.listBots(companyId),
            db.listMessages(companyId),
            db.listConnectors(companyId),
        )
    }

    suspend fun proposeCompany(description: String): CompanyProposalData = engine.proposeCompany(description)

    suspend fun deployCompany(description: String, proposal: CompanyProposalData): CompanyItem =
        engine.deployCompany(description, proposal)

    fun deleteCompany(companyId: String) {
        db.deleteCompany(companyId)
        sandbox.destroySandbox(companyId)
    }

    suspend fun sendFounderMessage(
        companyId: String,
        text: String,
        targetBotId: String? = null,
        onStatusUpdate: suspend (String) -> Unit = {},
    ) {
        engine.handleFounderMessage(companyId, text, targetBotId, onStatusUpdate)
    }

    fun requestBoardMeeting(companyId: String): BoardMeetingData = engine.generateBoardMeeting(companyId)

    suspend fun decideApproval(approvalId: String, decision: String, device: String = "Android", feedback: String = ""): ApprovalItem? =
        engine.decideApproval(approvalId, decision, device, feedback)

    fun clearBotUnread(botId: String) = db.clearBotUnread(botId)

    fun fetchArtifacts(companyId: String? = null): List<ArtifactItem> = db.listArtifacts(companyId)

    fun resolveArtifactFile(artifact: ArtifactItem): File? {
        return runCatching {
            sandbox.resolveFile(artifact.companyId, artifact.relPath).takeIf { it.exists() }
        }.getOrNull()
    }

    fun openOrShareArtifact(artifact: ArtifactItem): Boolean {
        val file = resolveArtifactFile(artifact) ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, artifact.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(viewIntent)
            true
        } catch (_: Exception) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = artifact.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, "Open / Share ${artifact.filename}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            true
        }
    }

    fun addConnector(companyId: String, name: String, baseUrl: String, authValue: String, live: Boolean) {
        db.insertConnector(
            companyId = companyId,
            id = "con_${UUID.randomUUID().toString().take(8)}",
            name = name,
            baseUrl = baseUrl,
            authHeader = "Authorization",
            authValue = authValue,
            live = live,
        )
    }

    fun getProviderStatuses(): List<ProviderStatus> = llm.getProviderStatuses()

    fun saveProviderKey(provider: String, apiKey: String, model: String) {
        llm.saveProviderKey(provider, apiKey, model)
    }

    suspend fun testProviderKey(provider: String): Pair<Boolean, String> = llm.testProvider(provider)

    fun updateBotModel(botId: String, model: String) = db.updateBotModel(botId, model)

    var killSwitch: Boolean
        get() = engine.killSwitch
        set(value) {
            engine.killSwitch = value
        }
}
