package com.foundryos.app.data

data class BotItem(
    val id: String,
    val companyId: String,
    val name: String,
    val role: String,
    val tagline: String,
    val avatarShape: String = "circle",
    val avatarColor: String = "#111111",
    val model: String = "auto",
    val allowedTools: List<String> = emptyList(),
    val isCeo: Boolean = false,
    val isQa: Boolean = false,
    val status: String = "idle",
    val statusText: String = "Ready",
    val unreadCount: Int = 0,
)

data class CompanyItem(
    val id: String,
    val name: String,
    val description: String,
    val missionStatement: String = "",
    val status: String = "ACTIVE",
    val botsCount: Int = 0,
    val pendingApprovals: Int = 0,
    val artifactsCount: Int = 0,
    val tokensUsedToday: Int = 0,
    val dailyTokenBudget: Int = 250000,
    val bots: List<BotItem> = emptyList(),
)

data class ArtifactItem(
    val id: String,
    val companyId: String,
    val botName: String,
    val title: String,
    val filename: String,
    val relPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val summary: String,
    val qaStatus: String = "PENDING",
    val qaScore: Int? = null,
    val qaReport: String = "",
    val createdAt: String = "",
)

data class ApprovalItem(
    val id: String,
    val companyId: String,
    val artifactId: String? = null,
    val title: String,
    val subtitle: String,
    val actionType: String,
    val status: String, // PENDING, APPROVED, REJECTED, EXECUTED, FAILED
    val decidedByDevice: String? = null,
    val feedback: String = "",
    val completionNote: String = "",
    val createdAt: String = "",
)

data class BoardMeetingData(
    val title: String,
    val sourcesCount: Int,
    val shipped: Int,
    val missions: Int,
    val qaPassed: Int,
    val decisionsApproved: Int,
    val tokensUsed: Int = 0,
    val tokenBudget: Int = 250000,
)

data class CompanyProposalData(
    val name: String,
    val missionStatement: String,
    val strategySummary: String,
    val recommendedConnectors: List<String>,
    val bots: List<BotItem>,
    val rawProposalJson: String,
)

data class ChatMessageItem(
    val id: String,
    val companyId: String,
    val senderType: String, // human | bot | system
    val botId: String? = null,
    val botName: String = "",
    val content: String = "",
    val cardType: String? = null, // company_org | artifact | approval | approval_confirmed | board_meeting
    val orgBots: List<BotItem> = emptyList(),
    val orgSubtitle: String = "",
    val orgCompanyName: String = "",
    val orgConnectors: List<String> = emptyList(),
    val artifact: ArtifactItem? = null,
    val approval: ApprovalItem? = null,
    val boardMeeting: BoardMeetingData? = null,
    val createdAt: String = "",
)

data class ConnectorItem(
    val id: String,
    val name: String,
    val baseUrl: String,
    val liveEnabled: Boolean,
)

data class ProviderStatus(
    val name: String,
    val configured: Boolean,
    val model: String,
)
