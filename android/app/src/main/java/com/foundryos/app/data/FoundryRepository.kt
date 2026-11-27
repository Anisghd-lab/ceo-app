package com.foundryos.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.foundryos.app.agy.AgyInstallProgress
import com.foundryos.app.agy.AgyRuntimeManager
import com.foundryos.app.data.BotLiveActivity
import com.foundryos.app.data.LiveReasoningState
import com.foundryos.app.engine.AutonomousEngine
import com.foundryos.app.engine.LocalDatabase
import com.foundryos.app.engine.OnDeviceLLMClient
import com.foundryos.app.engine.OnDeviceSandbox
import com.foundryos.app.plugins.PluginAuthManager
import com.foundryos.app.plugins.PluginLoginLaunch
import com.foundryos.app.plugins.PluginToolExecutor
import com.foundryos.app.updater.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Unified 100% On-Device Repository (Zero VPS / Zero External Server required).
 */
class FoundryRepository(private val context: Context) {
    companion object {
        @Volatile
        private var sharedAgy: AgyRuntimeManager? = null

        fun agyManager(context: Context): AgyRuntimeManager {
            return sharedAgy ?: synchronized(this) {
                sharedAgy ?: AgyRuntimeManager(context.applicationContext).also { sharedAgy = it }
            }
        }
    }

    private val db = LocalDatabase(context)
    private val sandbox = OnDeviceSandbox(context)
    private val llm = OnDeviceLLMClient(context)
    private val agy = agyManager(context)
    private val pluginAuth = PluginAuthManager(context.applicationContext)
    private val pluginExecutor = PluginToolExecutor(context.applicationContext, pluginAuth, sandbox)
    private val updater = AppUpdateManager(context.applicationContext)
    private val engine = AutonomousEngine(context, db, sandbox, llm, agy, pluginAuth, pluginExecutor)

    fun engineStatus(): Pair<Boolean, String> {
        val agyReady = agy.preferAgyEngine && agy.installer.isInstalled() && agy.accounts.readLiveToken() != null
        if (agyReady) {
            val email = agy.accounts.liveEmail().ifBlank { "Google Account" }
            return true to "Antigravity CLI · $email (${agy.selectedModel})"
        }
        if (llm.hasAnyKey()) {
            return true to llm.activeProviderSummary()
        }
        if (agy.installer.isInstalled()) {
            return false to "Antigravity CLI installed — sign in with Google in Settings"
        }
        return false to llm.activeProviderSummary()
    }

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

    fun inspectLocalUri(uri: Uri): AttachedLocalFile? {
        val resolver = context.contentResolver
        var name = "file_${System.currentTimeMillis() % 10000}"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cur ->
                val nameIdx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cur.getColumnIndex(OpenableColumns.SIZE)
                if (cur.moveToFirst()) {
                    if (nameIdx >= 0) name = cur.getString(nameIdx) ?: name
                    if (sizeIdx >= 0 && !cur.isNull(sizeIdx)) size = cur.getLong(sizeIdx)
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val isTextLike = mime.startsWith("text/") ||
            listOf("json", "xml", "csv", "md", "txt", "kt", "py", "js", "ts", "html", "yaml", "yml").any {
                name.lowercase().endsWith(".$it")
            }
        val snippet = if (isTextLike && size <= 256_000) {
            runCatching {
                resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(8000) }
            }.getOrNull()
        } else null

        return AttachedLocalFile(
            uriString = uri.toString(),
            name = name,
            sizeBytes = size,
            mimeType = mime,
            textSnippet = snippet,
        )
    }

    suspend fun importAttachmentsIntoSandbox(
        companyId: String,
        attachments: List<AttachedLocalFile>,
    ): String = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext ""
        val resolver = context.contentResolver
        val notes = StringBuilder("Attached local files imported into company sandbox:\n")
        for (att in attachments) {
            val uri = Uri.parse(att.uriString)
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                val saved = sandbox.writeUploadBytes(companyId, att.name, bytes)
                val rel = "workspace/uploads/${saved.name}"
                notes.append("- $rel (${bytes.size} bytes, ${att.mimeType})\n")
                if (!att.textSnippet.isNullOrBlank()) {
                    notes.append("  Content preview:\n${att.textSnippet.take(4000)}\n")
                }
            }
        }
        notes.toString()
    }

    suspend fun sendFounderMessage(
        companyId: String,
        text: String,
        attachments: List<AttachedLocalFile> = emptyList(),
        targetBotId: String? = null,
        onStatusUpdate: suspend (String) -> Unit = {},
    ) {
        val attachmentsContext = importAttachmentsIntoSandbox(companyId, attachments)
        val displayMsg = buildString {
            if (attachments.isNotEmpty()) {
                append(attachments.joinToString("\n") { "📎 ${it.name} (${it.sizeBytes} B)" })
                if (text.isNotBlank()) append("\n\n")
            }
            append(text)
        }.ifBlank { "Attached ${attachments.size} file(s)" }

        engine.handleFounderMessage(
            companyId = companyId,
            text = text.ifBlank { "Inspect and process the attached local files." },
            attachmentsSummary = attachmentsContext,
            displayMessage = displayMsg,
            targetBotId = targetBotId,
            onStatusUpdate = onStatusUpdate,
        )
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

    fun getProviderKey(provider: String): String = llm.getProviderKey(provider)

    fun getCachedModels(provider: String): List<RemoteModelInfo> = llm.getCachedModels(provider)

    fun addCustomProvider(name: String, baseUrl: String, protocol: String = "openai"): String =
        llm.addCustomProvider(name, baseUrl, protocol)

    fun deleteCustomProvider(providerId: String) = llm.deleteCustomProvider(providerId)

    suspend fun fetchAvailableModels(provider: String, apiKey: String) = llm.fetchAvailableModels(provider, apiKey)

    suspend fun saveAndTestModel(provider: String, modelId: String): Pair<Boolean, String> =
        llm.saveAndTestModel(provider, modelId)

    fun updateBotModel(botId: String, model: String) = db.updateBotModel(botId, model)

    fun sandboxesRootDir(): File = sandbox.sandboxesBaseDir()

    fun agyLoginUrlEvents(): SharedFlow<String> = agy.loginUrlEvents

    fun agyStateTick(): StateFlow<Long> = agy.stateTick

    fun isAgyPreferEnabled(): Boolean = agy.preferAgyEngine

    fun setAgyPreferEnabled(enabled: Boolean) {
        agy.preferAgyEngine = enabled
    }

    fun getAgySelectedModel(): String = agy.selectedModel

    fun setAgySelectedModel(modelId: String) {
        agy.selectedModel = modelId
    }

    suspend fun getAgyCliStatus(): AgyCliStatus = agy.getCliStatus()

    suspend fun ensureAgyHubStarted(): Boolean =
        if (agy.installer.isInstalled()) agy.ensureHubRunning(sandbox.sandboxesBaseDir()) else false

    suspend fun installOrUpdateAgy(onProgress: suspend (AgyInstallProgress) -> Unit = {}): Pair<Boolean, String> =
        agy.installer.installOrUpdate(onProgress)

    suspend fun getAgyAuthState(): AgyAuthState = agy.getAuthState(sandbox.sandboxesBaseDir())

    suspend fun startAgyGoogleLogin(onSignedIn: suspend () -> Unit = {}): Pair<Boolean, String> =
        agy.startGoogleLogin(sandbox.sandboxesBaseDir(), onSignedIn)

    suspend fun addAnotherAgyAccount(onSignedIn: suspend () -> Unit = {}): Pair<Boolean, String> =
        agy.addAnotherGoogleAccount(sandbox.sandboxesBaseDir(), onSignedIn)

    suspend fun switchAgyAccount(email: String): Pair<Boolean, String> =
        agy.switchGoogleAccount(email, sandbox.sandboxesBaseDir())

    fun saveCurrentAgyAccount(fallbackEmail: String = ""): Pair<Boolean, String> =
        agy.accounts.saveCurrentAccount(fallbackEmail)

    fun deleteAgyAccount(email: String): Boolean = agy.accounts.deleteSavedAccount(email)

    suspend fun fetchAgyModelsAndQuotas(): Pair<List<AgyModelInfo>, List<AgyQuotaGroup>> =
        agy.fetchModelsAndQuotas(sandbox.sandboxesBaseDir())

    fun getPluginStatuses(): List<PluginStatus> = pluginAuth.getPluginStatuses()

    fun getPluginToken(pluginId: String): String = pluginAuth.getAccessToken(pluginId)

    fun savePluginToken(pluginId: String, token: String, label: String = "") =
        pluginAuth.saveToken(pluginId, token, accountLabel = label)

    fun getPluginClientId(pluginId: String): String = pluginAuth.getCustomClientId(pluginId)

    fun setPluginClientId(pluginId: String, clientId: String) = pluginAuth.setCustomClientId(pluginId, clientId)

    fun disconnectPlugin(pluginId: String) = pluginAuth.disconnect(pluginId)

    suspend fun beginPluginConnectInBrowser(
        pluginId: String,
        onOAuthCompleted: suspend (Boolean, String) -> Unit = { _, _ -> },
    ): PluginLoginLaunch = pluginAuth.beginConnectInBrowser(pluginId, onOAuthCompleted)

    fun syncBrowserCookiesForUrl(url: String): Boolean = pluginAuth.syncBrowserCookiesForUrl(url)

    fun botTelemetry(): StateFlow<Map<String, BotLiveActivity>> = engine.botTelemetry

    fun liveReasoning(): StateFlow<LiveReasoningState> = engine.liveReasoning

    fun stopAllExecution() = engine.stopAllExecution()

    fun currentAppVersion(): String = updater.currentVersionName()

    suspend fun checkForAppUpdate(): AppUpdateInfo = updater.checkForUpdate()

    suspend fun downloadAndInstallUpdate(
        downloadUrl: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): Pair<Boolean, String> = updater.downloadAndInstall(downloadUrl, onProgress)

    var killSwitch: Boolean
        get() = engine.killSwitch
        set(value) {
            engine.killSwitch = value
        }
}
