package com.foundryos.app.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foundryos.app.data.AgyAuthState
import com.foundryos.app.data.AgyCliStatus
import com.foundryos.app.data.AgyModelInfo
import com.foundryos.app.data.AgyQuotaGroup
import com.foundryos.app.data.AppUpdateInfo
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.AttachedLocalFile
import com.foundryos.app.data.BotItem
import com.foundryos.app.data.ChatMessageItem
import com.foundryos.app.data.CompanyItem
import com.foundryos.app.data.CompanyProposalData
import com.foundryos.app.data.ConnectorItem
import com.foundryos.app.data.FoundryRepository
import com.foundryos.app.data.PluginStatus
import com.foundryos.app.data.ProviderStatus
import com.foundryos.app.service.ApprovalNotifier
import com.foundryos.app.ui.components.ArtifactDeliverableCard
import com.foundryos.app.ui.components.BoardMeetingCard
import com.foundryos.app.ui.components.BotMascot
import com.foundryos.app.ui.components.CompanyOrgCard
import com.foundryos.app.ui.components.ReleaseApprovalCard
import com.foundryos.app.ui.theme.FoundryColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Exact Video UI — 100% On-Device Android Engine (No VPS Required):
 * - Collapsible left rail with geometric mascots & notification badges
 * - Centered floating pill header: [Mascot] CEO
 * - Pure white stream with black founder bubbles, gray bot bubbles, Company Org / Deliverable QA / Release Ship / Board Meeting cards
 * - Bottom floating pill composer: [+] Message CEO [↑]
 */
@Composable
fun FoundryMainScreen(repo: FoundryRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var companies by remember { mutableStateOf<List<CompanyItem>>(emptyList()) }
    var activeCompanyId by remember { mutableStateOf<String?>(null) }
    var bots by remember { mutableStateOf<List<BotItem>>(emptyList()) }
    var messages by remember { mutableStateOf<List<ChatMessageItem>>(emptyList()) }
    var connectors by remember { mutableStateOf<List<ConnectorItem>>(emptyList()) }
    var artifacts by remember { mutableStateOf<List<ArtifactItem>>(emptyList()) }
    var selectedBot by remember { mutableStateOf<BotItem?>(null) }

    var sidebarExpanded by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<AttachedLocalFile>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val picked = uris.mapNotNull { repo.inspectLocalUri(it) }
            if (picked.isNotEmpty()) {
                pendingAttachments = (pendingAttachments + picked).distinctBy { it.uriString }
            }
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<CompanyProposalData?>(null) }
    var showArtifactsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBrowserDialog by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("https://www.google.com") }
    var browserIsOAuth by remember { mutableStateOf(false) }

    var agyCliStatus by remember { mutableStateOf(AgyCliStatus()) }
    var agyAuthState by remember { mutableStateOf(AgyAuthState()) }
    var agyPreferEngine by remember { mutableStateOf(repo.isAgyPreferEnabled()) }
    var agySelectedModel by remember { mutableStateOf(repo.getAgySelectedModel()) }
    var agyModels by remember { mutableStateOf<List<AgyModelInfo>>(emptyList()) }
    var agyQuotaGroups by remember { mutableStateOf<List<AgyQuotaGroup>>(emptyList()) }
    var agyBusyLabel by remember { mutableStateOf<String?>(null) }
    var agyInstallProgress by remember { mutableStateOf<Float?>(null) }

    var hasAnyKey by remember { mutableStateOf(false) }
    var engineSummary by remember { mutableStateOf("On-Device Engine") }
    val appVersion = remember { repo.currentAppVersion() }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateStatusLabel by remember { mutableStateOf<String?>(null) }
    var pluginStatuses by remember { mutableStateOf<List<PluginStatus>>(emptyList()) }
    var providers by remember { mutableStateOf<List<ProviderStatus>>(emptyList()) }
    var killSwitch by remember { mutableStateOf(repo.killSwitch) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var isTestingModel by remember { mutableStateOf(false) }
    var settingsMessage by remember { mutableStateOf<String?>(null) }
    var settingsSuccess by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    fun refreshAll(preferredCompanyId: String? = activeCompanyId) {
        val (keyOk, summary) = repo.engineStatus()
        hasAnyKey = keyOk
        engineSummary = summary
        pluginStatuses = repo.getPluginStatuses()
        providers = repo.getProviderStatuses()
        killSwitch = repo.killSwitch

        val list = repo.fetchCompanies()
        companies = list
        val chosen = preferredCompanyId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        activeCompanyId = chosen
        if (chosen != null) {
            val (b, m, c) = repo.fetchCompanyDetail(chosen)
            bots = b
            messages = m
            connectors = c
            artifacts = repo.fetchArtifacts(chosen)
            if (selectedBot == null || selectedBot?.companyId != chosen) {
                selectedBot = b.firstOrNull { it.isCeo } ?: b.firstOrNull()
            } else {
                selectedBot = b.firstOrNull { it.id == selectedBot?.id } ?: b.firstOrNull()
            }
        } else {
            bots = emptyList()
            messages = emptyList()
            connectors = emptyList()
            artifacts = emptyList()
            selectedBot = null
        }
    }

    suspend fun refreshAgyState(fetchQuotas: Boolean = false) {
        agyCliStatus = repo.getAgyCliStatus()
        agyPreferEngine = repo.isAgyPreferEnabled()
        agySelectedModel = repo.getAgySelectedModel()
        if (agyCliStatus.installed) {
            agyAuthState = repo.getAgyAuthState()
            if (fetchQuotas || (agyAuthState.signedIn && agyModels.isEmpty())) {
                val (m, q) = repo.fetchAgyModelsAndQuotas()
                agyModels = m
                agyQuotaGroups = q
            }
        }
        refreshAll(activeCompanyId)
    }

    LaunchedEffect(Unit) {
        refreshAll()
        refreshAgyState(fetchQuotas = true)
        appUpdateInfo = repo.checkForAppUpdate()
    }

    // Open Google OAuth sign-in URLs inside the in-app WebView browser automatically
    LaunchedEffect(repo) {
        repo.agyLoginUrlEvents().collect { url ->
            browserUrl = url
            browserIsOAuth = true
            showBrowserDialog = true
        }
    }

    // Refresh UI when Antigravity runtime emits state updates (account swap / completion / login)
    LaunchedEffect(repo) {
        repo.agyStateTick().collect {
            refreshAll(activeCompanyId)
        }
    }

    // Listen to instant UI refresh when user clicks "Ship" / "Not yet" in Android notification shade
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                refreshAll(activeCompanyId)
            }
        }
        val filter = IntentFilter(ApprovalNotifier.ACTION_REFRESH_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val activeCompany = companies.firstOrNull { it.id == activeCompanyId }
    val headerBot = selectedBot ?: bots.firstOrNull { it.isCeo }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FoundryColors.Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // === BASE LAYER: FIXED 64dp RAIL + FULL-WIDTH MAIN COCKPIT (Never crushes chat when drawer opens) ===
        Row(modifier = Modifier.fillMaxSize()) {
            // Collapsed 64dp Left Rail
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(64.dp)
                    .background(FoundryColors.SidebarBg)
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(7.dp).background(Color(0xFFFF5F57), CircleShape))
                    Box(Modifier.size(7.dp).background(Color(0xFFFEBC2E), CircleShape))
                    Box(Modifier.size(7.dp).background(Color(0xFF28C840), CircleShape))
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.dp, FoundryColors.Border, CircleShape)
                        .clickable { sidebarExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = "Expand", modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(bots) { bot ->
                        val pendingBadge = if (bot.isCeo) (activeCompany?.pendingApprovals ?: 0) else bot.unreadCount
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    repo.clearBotUnread(bot.id)
                                    selectedBot = bot
                                    refreshAll(activeCompanyId)
                                }
                                .padding(2.dp),
                        ) {
                            BotMascot(
                                shape = bot.avatarShape,
                                colorHex = bot.avatarColor,
                                size = 34.dp,
                                badgeCount = pendingBadge,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.dp, FoundryColors.Border, CircleShape)
                        .clickable {
                            proposal = null
                            showCreateDialog = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "New company", modifier = Modifier.size(18.dp))
                }
            }

            VerticalDivider(color = FoundryColors.Border, thickness = 0.8.dp)

            // === RIGHT MAIN COCKPIT ===
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Non-overlapping Top Bar: [Centered/Left-Weighted Bot Pill] + [Right Utility Icons]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                .clickable { sidebarExpanded = !sidebarExpanded }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BotMascot(
                                shape = headerBot?.avatarShape ?: "circle",
                                colorHex = headerBot?.avatarColor ?: "#111111",
                                size = 20.dp,
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = headerBot?.name ?: "CEO",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                                color = FoundryColors.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (activeCompany != null) {
                            IconButton(
                                onClick = {
                                    repo.requestBoardMeeting(activeCompany.id)
                                    refreshAll(activeCompany.id)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Rounded.Assessment, contentDescription = "Board meeting", tint = FoundryColors.InkSecondary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { showArtifactsDialog = true },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = "Deliverables", tint = FoundryColors.InkSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                browserUrl = "https://www.google.com"
                                browserIsOAuth = false
                                showBrowserDialog = true
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                contentDescription = "In-App Browser",
                                tint = FoundryColors.InkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "Settings",
                                tint = if (hasAnyKey) FoundryColors.InkSecondary else FoundryColors.AmberInk,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

            if (activeCompany == null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BotMascot(shape = "circle", colorHex = "#111111", size = 58.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("CEO", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "100% on-device. Describe any company below — your CEO dynamically hires the bots in an isolated sandbox and asks you before anything ships.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FoundryColors.InkSecondary,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(FoundryColors.InkPrimary)
                                .clickable {
                                    proposal = null
                                    showCreateDialog = true
                                }
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Create a company", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
                        }
                        if (!hasAnyKey) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(FoundryColors.SoftSurface)
                                    .clickable { showSettingsDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Connect AI", color = FoundryColors.InkPrimary, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Today · ${activeCompany.name} · on-device sandbox",
                                style = MaterialTheme.typography.labelSmall,
                                color = FoundryColors.InkMuted,
                            )
                        }
                    }

                    items(messages, key = { it.id }) { msg ->
                        ChatMessageRow(
                            msg = msg,
                            onBotClick = { bot ->
                                selectedBot = bots.firstOrNull { it.id == bot.id } ?: bot
                            },
                            onApprove = { aprId ->
                                scope.launch {
                                    repo.decideApproval(aprId, "APPROVED")
                                    refreshAll(activeCompany.id)
                                }
                            },
                            onReject = { aprId ->
                                scope.launch {
                                    repo.decideApproval(aprId, "REJECTED")
                                    refreshAll(activeCompany.id)
                                }
                            },
                            onDownloadArtifact = { art ->
                                val opened = repo.openOrShareArtifact(art)
                                if (!opened) {
                                    Toast.makeText(context, "File not found in sandbox", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }

                    if (isBusy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                BotMascot(shape = "circle", colorHex = "#111111", size = 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = busyLabel.ifBlank { "CEO is working..." },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = FoundryColors.InkSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Floating Pill Composer (+ attaches local device files!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingAttachments.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(FoundryColors.SidebarBg)
                                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = null,
                                    tint = FoundryColors.InkSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = file.name,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = FoundryColors.InkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove file",
                                    tint = FoundryColors.InkSecondary,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            pendingAttachments = pendingAttachments.filterNot { it.uriString == file.uriString }
                                        },
                                )
                            }
                        }
                    }
                }

                val canSend = (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) && !isBusy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White)
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(FoundryColors.SidebarBg)
                            .clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Attach local file", tint = FoundryColors.InkPrimary, modifier = Modifier.size(18.dp))
                    }

                    Spacer(Modifier.width(10.dp))

                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (activeCompany == null) "Describe a company to found..." else "Message ${headerBot?.name ?: "CEO"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FoundryColors.InkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(color = FoundryColors.InkPrimary, fontSize = 14.5.sp),
                            cursorBrush = SolidColor(FoundryColors.InkPrimary),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (canSend) FoundryColors.InkPrimary else FoundryColors.SoftSurface)
                            .clickable(enabled = canSend) {
                                val text = inputText.trim()
                                val filesToSend = pendingAttachments
                                inputText = ""
                                pendingAttachments = emptyList()
                                scope.launch {
                                    isBusy = true
                                    if (activeCompany == null) {
                                        val desc = text.ifBlank {
                                            "Company working with ${filesToSend.joinToString(", ") { it.name }}"
                                        }
                                        busyLabel = "CEO is hiring your company..."
                                        val prop = repo.proposeCompany(desc)
                                        val created = repo.deployCompany(desc, prop)
                                        if (filesToSend.isNotEmpty()) {
                                            busyLabel = "Importing local files into sandbox..."
                                            repo.sendFounderMessage(
                                                companyId = created.id,
                                                text = text.ifBlank { "Inspect and process the attached local files." },
                                                attachments = filesToSend,
                                                onStatusUpdate = { status ->
                                                    busyLabel = status
                                                    refreshAll(created.id)
                                                },
                                            )
                                        }
                                        refreshAll(created.id)
                                    } else {
                                        busyLabel = "${headerBot?.name ?: "CEO"} is working in sandbox..."
                                        val targetId = if (headerBot?.isCeo == true) null else headerBot?.id
                                        repo.sendFounderMessage(
                                            companyId = activeCompany.id,
                                            text = text,
                                            attachments = filesToSend,
                                            targetBotId = targetId,
                                            onStatusUpdate = { status ->
                                                busyLabel = status
                                                refreshAll(activeCompany.id)
                                            },
                                        )
                                        refreshAll(activeCompany.id)
                                    }
                                    isBusy = false
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (canSend) Color.White else FoundryColors.InkMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            }
        }

        // === OVERLAY EXPANDED SIDEBAR DRAWER (Preserves full chat width cleanly on mobile!) ===
        AnimatedVisibility(
            visible = sidebarExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f))
                    .clickable { sidebarExpanded = false },
            )
        }

        AnimatedVisibility(
            visible = sidebarExpanded,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            Surface(
                shadowElevation = 10.dp,
                color = FoundryColors.SidebarBg,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(248.dp),
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(9.dp).background(Color(0xFFFF5F57), CircleShape))
                                Box(Modifier.size(9.dp).background(Color(0xFFFEBC2E), CircleShape))
                                Box(Modifier.size(9.dp).background(Color(0xFF28C840), CircleShape))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(1.dp, FoundryColors.Border, CircleShape)
                                        .clickable {
                                            sidebarExpanded = false
                                            proposal = null
                                            showCreateDialog = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = "New company", modifier = Modifier.size(17.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(1.dp, FoundryColors.Border, CircleShape)
                                        .clickable { sidebarExpanded = false },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Collapse", modifier = Modifier.size(17.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(104.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFFEBEBED))
                                .clickable {
                                    selectedBot = bots.firstOrNull { it.isCeo }
                                    sidebarExpanded = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                BotMascot(shape = "circle", colorHex = "#111111", size = 46.dp)
                                Spacer(Modifier.height(6.dp))
                                Text("CEO", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = FoundryColors.InkPrimary)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = activeCompany?.name ?: "No company yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = FoundryColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (activeCompany != null) "1 human · ${bots.size} bots" else "Tap + to found one",
                            fontSize = 11.5.sp,
                            color = FoundryColors.InkSecondary,
                        )

                        Spacer(Modifier.height(10.dp))

                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(bots.filter { !it.isCeo }) { bot ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selectedBot?.id == bot.id) Color.White else Color.Transparent)
                                        .clickable {
                                            repo.clearBotUnread(bot.id)
                                            selectedBot = bot
                                            sidebarExpanded = false
                                            refreshAll(activeCompanyId)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BotMascot(shape = bot.avatarShape, colorHex = bot.avatarColor, size = 30.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            bot.name,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = FoundryColors.InkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            bot.statusText.ifBlank { bot.tagline },
                                            fontSize = 11.sp,
                                            color = FoundryColors.InkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }

                            if (companies.size > 1) {
                                item {
                                    Spacer(Modifier.height(8.dp))
                                    HorizontalDivider(color = FoundryColors.Border)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Sandboxes (${companies.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkMuted)
                                }
                                items(companies) { comp ->
                                    val active = comp.id == activeCompanyId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (active) Color.White else Color.Transparent)
                                            .border(if (active) 1.dp else 0.dp, if (active) FoundryColors.Border else Color.Transparent, RoundedCornerShape(10.dp))
                                            .clickable {
                                                selectedBot = null
                                                refreshAll(comp.id)
                                                sidebarExpanded = false
                                            }
                                            .padding(horizontal = 8.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .background(if (active) FoundryColors.GreenSolid else FoundryColors.InkMuted, CircleShape)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            comp.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    VerticalDivider(color = FoundryColors.Border, thickness = 0.8.dp)
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCompanyDialog(
            isLoading = isBusy,
            proposal = proposal,
            onPropose = { desc ->
                scope.launch {
                    isBusy = true
                    proposal = repo.proposeCompany(desc)
                    isBusy = false
                }
            },
            onDeploy = { desc, prop ->
                scope.launch {
                    isBusy = true
                    val created = repo.deployCompany(desc, prop)
                    showCreateDialog = false
                    proposal = null
                    isBusy = false
                    refreshAll(created.id)
                }
            },
            onResetProposal = { proposal = null },
            onDismiss = { showCreateDialog = false },
        )
    }

    if (showArtifactsDialog && activeCompany != null) {
        ArtifactsDialog(
            companyName = activeCompany.name,
            artifacts = artifacts,
            onDownload = { art ->
                val ok = repo.openOrShareArtifact(art)
                if (!ok) Toast.makeText(context, "File not found in sandbox", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showArtifactsDialog = false },
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            engineSummary = engineSummary,
            hasAnyKey = hasAnyKey,
            agyCliStatus = agyCliStatus,
            agyAuthState = agyAuthState,
            agyPreferEngine = agyPreferEngine,
            agySelectedModel = agySelectedModel,
            agyModels = agyModels,
            agyQuotaGroups = agyQuotaGroups,
            agyBusyLabel = agyBusyLabel,
            agyInstallProgress = agyInstallProgress,
            appVersion = appVersion,
            updateInfo = appUpdateInfo,
            updateProgress = updateProgress,
            updateStatusLabel = updateStatusLabel,
            plugins = pluginStatuses,
            providers = providers,
            connectors = connectors,
            killSwitch = killSwitch,
            isFetchingModels = isFetchingModels,
            isTestingModel = isTestingModel,
            statusMessage = settingsMessage,
            statusIsSuccess = settingsSuccess,
            getSavedKey = { prov -> repo.getProviderKey(prov) },
            getCachedModels = { prov -> repo.getCachedModels(prov) },
            getPluginToken = { id -> repo.getPluginToken(id) },
            getPluginClientId = { id -> repo.getPluginClientId(id) },
            onTogglePreferAgy = { enabled ->
                repo.setAgyPreferEnabled(enabled)
                agyPreferEngine = enabled
                refreshAll(activeCompanyId)
            },
            onInstallOrUpdateAgy = {
                scope.launch {
                    agyBusyLabel = "Preparing Antigravity CLI download..."
                    agyInstallProgress = 0f
                    val (ok, msg) = repo.installOrUpdateAgy { p ->
                        agyBusyLabel = p.detail
                        agyInstallProgress = p.progress.takeIf { it > 0f }
                    }
                    agyInstallProgress = null
                    agyBusyLabel = if (ok) "Starting Antigravity Hub..." else null
                    if (ok) {
                        repo.ensureAgyHubStarted()
                    }
                    agyBusyLabel = null
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    refreshAgyState(fetchQuotas = ok)
                }
            },
            onAgyLogin = {
                scope.launch {
                    agyBusyLabel = "Launching Google OAuth sign-in..."
                    val (ok, msg) = repo.startAgyGoogleLogin {
                        refreshAgyState(fetchQuotas = true)
                    }
                    agyBusyLabel = null
                    if (!ok) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    refreshAgyState(fetchQuotas = ok)
                }
            },
            onAgyAddAnotherAccount = {
                scope.launch {
                    agyBusyLabel = "Saving current Google account & opening new OAuth..."
                    val (ok, msg) = repo.addAnotherAgyAccount {
                        refreshAgyState(fetchQuotas = true)
                    }
                    agyBusyLabel = null
                    if (!ok) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    refreshAgyState(fetchQuotas = ok)
                }
            },
            onAgySwitchAccount = { email ->
                scope.launch {
                    agyBusyLabel = "Switching to $email..."
                    val (_, msg) = repo.switchAgyAccount(email)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    refreshAgyState(fetchQuotas = true)
                    agyBusyLabel = null
                }
            },
            onAgySaveAccount = {
                val (_, msg) = repo.saveCurrentAgyAccount(agyAuthState.email)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                scope.launch { refreshAgyState(fetchQuotas = false) }
            },
            onAgyDeleteAccount = { email ->
                repo.deleteAgyAccount(email)
                Toast.makeText(context, "Removed saved account $email", Toast.LENGTH_SHORT).show()
                scope.launch { refreshAgyState(fetchQuotas = false) }
            },
            onRefreshAgyQuotas = {
                scope.launch {
                    agyBusyLabel = "Fetching models & Google account quotas..."
                    refreshAgyState(fetchQuotas = true)
                    agyBusyLabel = null
                }
            },
            onSelectAgyModel = { modelId ->
                repo.setAgySelectedModel(modelId)
                agySelectedModel = modelId
                refreshAll(activeCompanyId)
                Toast.makeText(context, "Antigravity model set to $modelId", Toast.LENGTH_SHORT).show()
            },
            onConnectPluginInBrowser = { pluginId ->
                scope.launch {
                    val launch = repo.beginPluginConnectInBrowser(pluginId) { ok, msg ->
                        refreshAll(activeCompanyId)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        if (ok) showBrowserDialog = false
                    }
                    if (launch.ok && launch.urlToOpen.isNotBlank()) {
                        browserUrl = launch.urlToOpen
                        browserIsOAuth = launch.isOAuthLoopback
                        showBrowserDialog = true
                    }
                    if (launch.message.isNotBlank()) {
                        Toast.makeText(context, launch.message, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSavePluginToken = { pluginId, token ->
                repo.savePluginToken(pluginId, token)
                refreshAll(activeCompanyId)
                Toast.makeText(context, "Saved $pluginId credentials", Toast.LENGTH_SHORT).show()
            },
            onSavePluginClientId = { pluginId, clientId ->
                repo.setPluginClientId(pluginId, clientId)
                refreshAll(activeCompanyId)
                Toast.makeText(context, "Saved OAuth Client ID for $pluginId", Toast.LENGTH_SHORT).show()
            },
            onDisconnectPlugin = { pluginId ->
                repo.disconnectPlugin(pluginId)
                refreshAll(activeCompanyId)
                Toast.makeText(context, "Disconnected $pluginId", Toast.LENGTH_SHORT).show()
            },
            onCheckAppUpdate = {
                scope.launch {
                    updateStatusLabel = "Checking GitHub Releases..."
                    updateProgress = null
                    val info = repo.checkForAppUpdate()
                    appUpdateInfo = info
                    updateStatusLabel = null
                    val msg = when {
                        info.error.isNotBlank() -> info.error
                        info.hasUpdate -> "Update v${info.latestVersion} available!"
                        else -> "CEO v${info.currentVersion} is up to date."
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onInstallAppUpdate = { downloadUrl ->
                scope.launch {
                    updateStatusLabel = "Starting update download..."
                    updateProgress = 0f
                    val (ok, msg) = repo.downloadAndInstallUpdate(downloadUrl) { frac, detail ->
                        updateProgress = frac
                        updateStatusLabel = detail
                    }
                    updateProgress = null
                    updateStatusLabel = null
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
            onAddCustomProvider = { name, baseUrl, protocol ->
                val slug = repo.addCustomProvider(name, baseUrl, protocol)
                settingsSuccess = true
                settingsMessage = "Provider '$slug' added — now enter your API key & load models."
                refreshAll(activeCompanyId)
                slug
            },
            onDeleteCustomProvider = { prov ->
                repo.deleteCustomProvider(prov)
                settingsMessage = "Removed provider '$prov'."
                settingsSuccess = true
                refreshAll(activeCompanyId)
            },
            onFetchModels = { prov, key ->
                scope.launch {
                    isFetchingModels = true
                    settingsMessage = null
                    val res = repo.fetchAvailableModels(prov, key)
                    settingsSuccess = res.ok
                    settingsMessage = res.message
                    isFetchingModels = false
                    refreshAll(activeCompanyId)
                }
            },
            onSelectAndTestModel = { prov, modelId ->
                scope.launch {
                    isTestingModel = true
                    settingsMessage = null
                    val (ok, msg) = repo.saveAndTestModel(prov, modelId)
                    settingsSuccess = ok
                    settingsMessage = msg
                    isTestingModel = false
                    refreshAll(activeCompanyId)
                }
            },
            onAddConnector = { name, url, auth, live ->
                val cid = activeCompanyId ?: return@SettingsDialog
                repo.addConnector(cid, name, url, auth, live)
                Toast.makeText(context, "Connector '$name' attached", Toast.LENGTH_SHORT).show()
                refreshAll(cid)
            },
            onToggleKillSwitch = { active ->
                repo.killSwitch = active
                killSwitch = active
            },
            onDismiss = {
                settingsMessage = null
                showSettingsDialog = false
            },
        )
    }

    if (showBrowserDialog) {
        InAppBrowserDialog(
            initialUrl = browserUrl,
            isOAuthSession = browserIsOAuth,
            onOAuthLoopbackHit = {
                scope.launch {
                    delay(1200)
                    refreshAgyState(fetchQuotas = true)
                    refreshAll(activeCompanyId)
                    Toast.makeText(context, "Sign-In completed!", Toast.LENGTH_SHORT).show()
                    showBrowserDialog = false
                }
            },
            onUrlVisited = { visitedUrl ->
                if (repo.syncBrowserCookiesForUrl(visitedUrl)) {
                    refreshAll(activeCompanyId)
                }
            },
            onDismiss = {
                showBrowserDialog = false
                scope.launch { refreshAgyState(fetchQuotas = browserIsOAuth) }
            },
        )
    }
}

@Composable
private fun ChatMessageRow(
    msg: ChatMessageItem,
    onBotClick: (BotItem) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onDownloadArtifact: (ArtifactItem) -> Unit,
) {
    when {
        msg.senderType == "human" -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(FoundryColors.InkPrimary)
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = msg.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                    )
                }
            }
        }
        msg.cardType == "approval_confirmed" -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = FoundryColors.InkSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(msg.content, style = MaterialTheme.typography.labelSmall, color = FoundryColors.InkSecondary)
            }
        }
        msg.cardType == "company_org" -> {
            CompanyOrgCard(
                companyName = msg.orgCompanyName,
                subtitle = msg.orgSubtitle,
                bots = msg.orgBots,
                connectors = msg.orgConnectors,
                onBotClick = onBotClick,
            )
        }
        msg.cardType == "artifact" && msg.artifact != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.labelMedium,
                    color = FoundryColors.InkSecondary,
                    modifier = Modifier.padding(start = 4.dp),
                )
                ArtifactDeliverableCard(
                    artifact = msg.artifact,
                    onDownloadClick = { onDownloadArtifact(msg.artifact) },
                )
            }
        }
        msg.cardType == "approval" && msg.approval != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (msg.content.isNotBlank()) {
                    BotBubble(text = msg.content)
                }
                ReleaseApprovalCard(
                    approval = msg.approval,
                    onApprove = { onApprove(msg.approval.id) },
                    onReject = { onReject(msg.approval.id) },
                )
            }
        }
        msg.cardType == "board_meeting" && msg.boardMeeting != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (msg.content.isNotBlank()) {
                    BotBubble(text = msg.content)
                }
                BoardMeetingCard(data = msg.boardMeeting)
            }
        }
        else -> {
            BotBubble(text = msg.content)
        }
    }
}

@Composable
private fun BotBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(18.dp))
                .background(FoundryColors.SoftSurface)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = FoundryColors.InkPrimary,
            )
        }
    }
}
