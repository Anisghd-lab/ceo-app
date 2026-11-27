package com.foundryos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foundryos.app.data.AgyAuthState
import com.foundryos.app.data.AgyCliStatus
import com.foundryos.app.data.AgyModelInfo
import com.foundryos.app.data.AgyQuotaGroup
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.CompanyProposalData
import com.foundryos.app.data.ConnectorItem
import com.foundryos.app.data.PluginAuthType
import com.foundryos.app.data.PluginStatus
import com.foundryos.app.data.ProviderStatus
import com.foundryos.app.data.RemoteModelInfo
import com.foundryos.app.ui.components.ArtifactDeliverableCard
import com.foundryos.app.ui.components.CompanyOrgCard
import com.foundryos.app.ui.theme.FoundryColors

/**
 * 100% Free-form Company Founding Dialog (zero hardcoded industry):
 * Describe ANY business -> CEO dynamically designs the bot org-chart -> Deploy isolated sandbox on-device.
 */
@Composable
fun CreateCompanyDialog(
    isLoading: Boolean,
    proposal: CompanyProposalData?,
    onPropose: (String) -> Unit,
    onDeploy: (String, CompanyProposalData) -> Unit,
    onResetProposal: () -> Unit,
    onDismiss: () -> Unit,
) {
    var description by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(24.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Found a new company", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "1 Sandbox = 1 Company · On-device CEO designs the team",
                        style = MaterialTheme.typography.labelMedium,
                        color = FoundryColors.InkSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Describe your company freely") },
                placeholder = {
                    Text("Ex: Create a digital products company that creates quality PDFs, verifies them, and prepares store listings — or any other business...")
                },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            if (proposal == null) {
                Button(
                    onClick = { if (description.trim().length >= 5) onPropose(description.trim()) },
                    enabled = !isLoading && description.trim().length >= 5,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FoundryColors.InkPrimary),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("CEO is designing your team...")
                    } else {
                        Text("Ask CEO to design team", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    text = proposal.strategySummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FoundryColors.InkSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(FoundryColors.SidebarBg)
                        .padding(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                CompanyOrgCard(
                    companyName = proposal.name,
                    subtitle = "1 human · ${proposal.bots.size} bots · dedicated sandbox",
                    bots = proposal.bots,
                    connectors = proposal.recommendedConnectors,
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onResetProposal,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Adjust", color = FoundryColors.InkPrimary)
                    }
                    Button(
                        onClick = { onDeploy(description.trim(), proposal) },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoundryColors.InkPrimary),
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text("Deploy Sandbox", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ArtifactsDialog(
    companyName: String,
    artifacts: List<ArtifactItem>,
    onDownload: (ArtifactItem) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(24.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sandbox Deliverables", style = MaterialTheme.typography.titleLarge)
                    Text(companyName, style = MaterialTheme.typography.labelMedium, color = FoundryColors.InkSecondary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (artifacts.isEmpty()) {
                Text(
                    "No files generated in this sandbox yet. Message your CEO to start a mission.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FoundryColors.InkSecondary,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                artifacts.forEach { art ->
                    ArtifactDeliverableCard(artifact = art, onDownloadClick = { onDownload(art) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    engineSummary: String,
    hasAnyKey: Boolean,
    agyCliStatus: AgyCliStatus,
    agyAuthState: AgyAuthState,
    agyPreferEngine: Boolean,
    agySelectedModel: String,
    agyModels: List<AgyModelInfo>,
    agyQuotaGroups: List<AgyQuotaGroup>,
    agyBusyLabel: String?,
    agyInstallProgress: Float?,
    plugins: List<PluginStatus>,
    providers: List<ProviderStatus>,
    connectors: List<ConnectorItem>,
    killSwitch: Boolean,
    isFetchingModels: Boolean,
    isTestingModel: Boolean,
    statusMessage: String?,
    statusIsSuccess: Boolean,
    getSavedKey: (String) -> String,
    getCachedModels: (String) -> List<RemoteModelInfo>,
    getPluginToken: (String) -> String,
    getPluginClientId: (String) -> String,
    onTogglePreferAgy: (Boolean) -> Unit,
    onInstallOrUpdateAgy: () -> Unit,
    onAgyLogin: () -> Unit,
    onAgyAddAnotherAccount: () -> Unit,
    onAgySwitchAccount: (String) -> Unit,
    onAgySaveAccount: () -> Unit,
    onAgyDeleteAccount: (String) -> Unit,
    onRefreshAgyQuotas: () -> Unit,
    onSelectAgyModel: (String) -> Unit,
    onConnectPluginInBrowser: (String) -> Unit,
    onSavePluginToken: (String, String) -> Unit,
    onSavePluginClientId: (String, String) -> Unit,
    onDisconnectPlugin: (String) -> Unit,
    onAddCustomProvider: (String, String, String) -> String,
    onDeleteCustomProvider: (String) -> Unit,
    onFetchModels: (String, String) -> Unit,
    onSelectAndTestModel: (String, String) -> Unit,
    onAddConnector: (String, String, String, Boolean) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProvider by remember {
        mutableStateOf(providers.firstOrNull { it.configured }?.name ?: "openrouter")
    }
    var showAddProviderForm by remember { mutableStateOf(false) }
    var customProvName by remember { mutableStateOf("") }
    var customProvUrl by remember { mutableStateOf("") }
    var customProvProtocol by remember { mutableStateOf("openai") }

    var apiKeyInput by remember { mutableStateOf(getSavedKey(selectedProvider)) }
    var showKey by remember { mutableStateOf(false) }
    var selectedModel by remember {
        mutableStateOf(providers.firstOrNull { it.name == selectedProvider }?.model ?: "")
    }
    var modelSearch by remember { mutableStateOf("") }

    LaunchedEffect(selectedProvider, providers) {
        apiKeyInput = getSavedKey(selectedProvider)
        selectedModel = providers.firstOrNull { it.name == selectedProvider }?.model ?: ""
        modelSearch = ""
    }

    val currentProviderInfo = providers.firstOrNull { it.name == selectedProvider }
    val liveModels = getCachedModels(selectedProvider)
    val filteredModels = remember(liveModels, modelSearch) {
        val q = modelSearch.trim().lowercase()
        if (q.isBlank()) liveModels
        else liveModels.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
    }

    var connName by remember { mutableStateOf("") }
    var connUrl by remember { mutableStateOf("") }
    var connAuth by remember { mutableStateOf("") }
    var connLive by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(24.dp))
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CEO Engine & Keys", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "100% On-Device · No VPS needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = FoundryColors.InkSecondary,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = engineSummary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (hasAnyKey) FoundryColors.GreenInk else FoundryColors.AmberInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (hasAnyKey) FoundryColors.GreenBg else Color(0xFFFFFBEB))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )

            Spacer(Modifier.height(14.dp))

            // === ANTIGRAVITY CLI + MULTI-GOOGLE ACCOUNTS + LIVE MODEL QUOTAS ===
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(FoundryColors.SidebarBg)
                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(18.dp))
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Text("Antigravity CLI (On-Device)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = when {
                                    !agyCliStatus.supportedAbi -> "ARM64 Android required"
                                    agyCliStatus.installed -> "Installed · ${agyCliStatus.version.ifBlank { "ready" }}"
                                    else -> "Not installed (~native binary, 0 Termux)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (agyCliStatus.installed) FoundryColors.GreenInk else FoundryColors.InkSecondary,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 38.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (agyBusyLabel != null) FoundryColors.SoftSurface else FoundryColors.InkPrimary)
                            .clickable(enabled = agyCliStatus.supportedAbi && agyBusyLabel == null) { onInstallOrUpdateAgy() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                tint = if (agyBusyLabel != null) FoundryColors.InkSecondary else Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (agyCliStatus.installed) "Update CLI" else "Install CLI",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                color = if (agyBusyLabel != null) FoundryColors.InkSecondary else Color.White,
                            )
                        }
                    }
                }

                if (agyBusyLabel != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(agyBusyLabel, fontSize = 11.5.sp, color = FoundryColors.BlueInk, fontWeight = FontWeight.Medium)
                    if (agyInstallProgress != null && agyInstallProgress > 0f) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { agyInstallProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
                            color = FoundryColors.InkPrimary,
                            trackColor = FoundryColors.Border,
                        )
                    } else {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(999.dp)),
                            color = FoundryColors.InkPrimary,
                            trackColor = FoundryColors.Border,
                        )
                    }
                }

                if (agyCliStatus.installed) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Run CEO via Antigravity CLI", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkPrimary)
                            Text("Uses your Google Antigravity quotas", fontSize = 11.sp, color = FoundryColors.InkSecondary)
                        }
                        Switch(checked = agyPreferEngine, onCheckedChange = onTogglePreferAgy)
                    }

                    Spacer(Modifier.height(10.dp))
                    // Multi-Account Google Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (agyAuthState.signedIn) agyAuthState.email.ifBlank { "Google Account Active" }
                                else "No Google account connected",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (agyAuthState.signedIn) FoundryColors.InkPrimary else FoundryColors.AmberInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!agyAuthState.signedIn) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(FoundryColors.InkPrimary)
                                    .clickable(enabled = agyBusyLabel == null) { onAgyLogin() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("Sign in with Google", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(FoundryColors.InkPrimary)
                                    .clickable(enabled = agyBusyLabel == null) { onAgyAddAnotherAccount() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("+ Google Account", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                            }
                            if (!agyAuthState.currentSaved) {
                                Box(
                                    modifier = Modifier
                                        .heightIn(min = 38.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color.White)
                                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                        .clickable { onAgySaveAccount() }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("Save Account", color = FoundryColors.InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .heightIn(min = 38.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                .clickable(enabled = agyBusyLabel == null) { onRefreshAgyQuotas() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Refresh Quotas", color = FoundryColors.InkPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    if (agyAuthState.accounts.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Saved Google Accounts (${agyAuthState.accounts.size})", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp)),
                        ) {
                            agyAuthState.accounts.forEachIndexed { idx, acc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(acc.email, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = FoundryColors.InkPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (acc.active) "Active session" else acc.authMethod,
                                            fontSize = 10.5.sp,
                                            color = if (acc.active) FoundryColors.GreenInk else FoundryColors.InkMuted,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (acc.active) {
                                            Text(
                                                "Active",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = FoundryColors.GreenInk,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(FoundryColors.GreenBg)
                                                    .padding(horizontal = 9.dp, vertical = 4.dp),
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .heightIn(min = 32.dp)
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(FoundryColors.SoftSurface)
                                                    .clickable(enabled = agyBusyLabel == null) { onAgySwitchAccount(acc.email) }
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(13.dp))
                                                    Spacer(Modifier.width(3.dp))
                                                    Text("Switch", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkPrimary, maxLines = 1, softWrap = false)
                                                }
                                            }
                                        }
                                        IconButton(
                                            onClick = { onAgyDeleteAccount(acc.email) },
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Remove account", tint = FoundryColors.InkMuted, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                                if (idx < agyAuthState.accounts.lastIndex) {
                                    HorizontalDivider(color = FoundryColors.Border, thickness = 0.5.dp)
                                }
                            }
                        }
                    }

                    // Live Quota Bars (5h & Weekly)
                    if (agyQuotaGroups.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Account Quota Windows", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            agyQuotaGroups.forEach { group ->
                                val buckets = listOfNotNull(group.fiveHour, group.weekly)
                                if (buckets.isNotEmpty()) {
                                    Text(group.groupName, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkPrimary)
                                    buckets.forEach { b ->
                                        val pct = (b.remainingFraction.coerceIn(0f, 1f) * 100f).toInt()
                                        val barColor = when {
                                            pct >= 40 -> FoundryColors.GreenInk
                                            pct >= 15 -> FoundryColors.AmberInk
                                            else -> FoundryColors.RedInk
                                        }
                                        Column {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(b.displayName.ifBlank { b.window }, fontSize = 11.sp, color = FoundryColors.InkSecondary)
                                                Text(
                                                    text = if (b.countdown.isNotBlank()) "${b.remainingPct} · ${b.countdown}" else b.remainingPct,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = barColor,
                                                )
                                            }
                                            Spacer(Modifier.height(3.dp))
                                            LinearProgressIndicator(
                                                progress = { b.remainingFraction.coerceIn(0f, 1f) },
                                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(999.dp)),
                                                color = barColor,
                                                trackColor = FoundryColors.SoftSurface,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Antigravity Models + Per-Model Quotas
                    if (agyModels.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Antigravity Models & Quotas (${agyModels.size})", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
                        Spacer(Modifier.height(4.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp)),
                        ) {
                            agyModels.forEachIndexed { idx, m ->
                                val isChosen = m.id == agySelectedModel
                                val qPct = m.remainingFraction?.let { (it.coerceIn(0f, 1f) * 100f).toInt() }
                                val qColor = when {
                                    qPct == null -> FoundryColors.InkSecondary
                                    qPct >= 40 -> FoundryColors.GreenInk
                                    qPct >= 15 -> FoundryColors.AmberInk
                                    else -> FoundryColors.RedInk
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isChosen) FoundryColors.SoftSurface else Color.White)
                                        .clickable { onSelectAgyModel(m.id) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        if (isChosen) {
                                            Icon(Icons.Rounded.Check, contentDescription = null, tint = FoundryColors.GreenInk, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Column {
                                            Text(
                                                text = m.displayName.ifBlank { m.id },
                                                fontSize = 12.sp,
                                                fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                                color = FoundryColors.InkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = m.id,
                                                fontSize = 10.5.sp,
                                                color = FoundryColors.InkMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = when {
                                            qPct != null && m.resetCountdown.isNotBlank() -> "$qPct% · ${m.resetCountdown}"
                                            qPct != null -> "$qPct%"
                                            else -> "Ready"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = qColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(FoundryColors.SidebarBg)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                                if (idx < agyModels.lastIndex) {
                                    HorizontalDivider(color = FoundryColors.Border, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))

            // === PRE-CONFIGURED PLUGINS & APPS (CHATGPT STYLE -> CONNECT VIA IN-APP BROWSER) ===
            var expandedPluginId by remember { mutableStateOf<String?>(null) }
            var pluginTokenDraft by remember { mutableStateOf("") }
            var pluginClientIdDraft by remember { mutableStateOf("") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Extension, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text("Plugins & Apps (${plugins.count { it.connected }}/${plugins.size} active)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pre-configured for CEO & all bots · Connect in CEO Browser",
                            style = MaterialTheme.typography.labelSmall,
                            color = FoundryColors.InkSecondary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(FoundryColors.SidebarBg)
                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(16.dp)),
            ) {
                plugins.forEachIndexed { idx, ps ->
                    val def = ps.definition
                    val isExp = expandedPluginId == def.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(def.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkPrimary)
                                    Text(
                                        text = if (ps.connected) "Connected ✓" else def.category,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (ps.connected) FoundryColors.GreenInk else FoundryColors.InkSecondary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (ps.connected) FoundryColors.GreenBg else Color.White)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                    )
                                }
                                Text(
                                    text = def.tagline,
                                    fontSize = 11.sp,
                                    color = FoundryColors.InkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${ps.accountLabel} · ${def.tools.size} tool(s)",
                                    fontSize = 10.5.sp,
                                    color = if (ps.connected) FoundryColors.GreenInk else FoundryColors.InkMuted,
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (def.authType != PluginAuthType.NONE_READY) {
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 36.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (ps.connected) Color.White else FoundryColors.InkPrimary)
                                            .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                            .clickable { onConnectPluginInBrowser(def.id) }
                                            .padding(horizontal = 11.dp, vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Rounded.Public,
                                                contentDescription = null,
                                                tint = if (ps.connected) FoundryColors.InkPrimary else Color.White,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = if (ps.connected) "Open" else "Connect",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false,
                                                color = if (ps.connected) FoundryColors.InkPrimary else Color.White,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            if (isExp) {
                                                expandedPluginId = null
                                            } else {
                                                expandedPluginId = def.id
                                                pluginTokenDraft = getPluginToken(def.id)
                                                pluginClientIdDraft = getPluginClientId(def.id)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(Icons.Rounded.VpnKey, contentDescription = "Token / OAuth Config", tint = FoundryColors.InkSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        if (isExp && def.authType != PluginAuthType.NONE_READY) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                            ) {
                                OutlinedTextField(
                                    value = pluginTokenDraft,
                                    onValueChange = { pluginTokenDraft = it },
                                    label = { Text("${def.name} Token / API Key (optional if signed in via Browser)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (def.authType == PluginAuthType.OAUTH_PKCE || def.authType == PluginAuthType.MCP_OAUTH || def.authType == PluginAuthType.DEVICE_FLOW) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = pluginClientIdDraft,
                                        onValueChange = { pluginClientIdDraft = it },
                                        label = { Text("OAuth Client ID (optional, for 1-click PKCE)") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 38.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(FoundryColors.InkPrimary)
                                            .clickable {
                                                if (pluginTokenDraft.isNotBlank()) onSavePluginToken(def.id, pluginTokenDraft.trim())
                                                if (pluginClientIdDraft.isNotBlank()) onSavePluginClientId(def.id, pluginClientIdDraft.trim())
                                                expandedPluginId = null
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Save", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    }
                                    if (ps.connected) {
                                        Box(
                                            modifier = Modifier
                                                .heightIn(min = 38.dp)
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(FoundryColors.RedBg)
                                                .clickable {
                                                    onDisconnectPlugin(def.id)
                                                    pluginTokenDraft = ""
                                                    expandedPluginId = null
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Rounded.LinkOff, contentDescription = null, tint = FoundryColors.RedInk, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Disconnect", color = FoundryColors.RedInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (idx < plugins.lastIndex) {
                        HorizontalDivider(color = FoundryColors.Border, thickness = 0.5.dp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))
            Text("External API Keys (Optional Fallback)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providers.forEach { p ->
                    val active = !showAddProviderForm && selectedProvider == p.name
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (active) FoundryColors.InkPrimary else FoundryColors.SoftSurface)
                            .clickable {
                                showAddProviderForm = false
                                selectedProvider = p.name
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = if (p.configured) "${p.name} ✓" else p.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = if (active) Color.White else FoundryColors.InkPrimary,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (showAddProviderForm) FoundryColors.InkPrimary else FoundryColors.LavenderBg)
                        .border(1.dp, if (showAddProviderForm) FoundryColors.InkPrimary else FoundryColors.Border, RoundedCornerShape(999.dp))
                        .clickable { showAddProviderForm = !showAddProviderForm }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "+ Add Provider",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (showAddProviderForm) Color.White else FoundryColors.LavenderInk,
                    )
                }
            }

            if (showAddProviderForm) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(FoundryColors.SidebarBg)
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text("Add Custom API Provider", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Works with Mistral, Together, Fireworks, Ollama, LM Studio, vLLM, or any OpenAI/Anthropic compatible endpoint.",
                        style = MaterialTheme.typography.labelSmall,
                        color = FoundryColors.InkSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customProvName,
                        onValueChange = { customProvName = it },
                        label = { Text("Provider Name (e.g. mistral, together, local)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customProvUrl,
                        onValueChange = { customProvUrl = it },
                        label = { Text("API Base URL (e.g. https://api.mistral.ai/v1)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("openai" to "OpenAI-compat", "anthropic" to "Anthropic-compat").forEach { (proto, label) ->
                            val isSelected = customProvProtocol == proto
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isSelected) FoundryColors.InkPrimary else Color.White)
                                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                                    .clickable { customProvProtocol = proto }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(label, fontSize = 11.5.sp, color = if (isSelected) Color.White else FoundryColors.InkPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (customProvName.isNotBlank() && customProvUrl.trim().startsWith("http")) {
                                val newId = onAddCustomProvider(customProvName.trim(), customProvUrl.trim(), customProvProtocol)
                                selectedProvider = newId
                                customProvName = ""
                                customProvUrl = ""
                                showAddProviderForm = false
                            }
                        },
                        enabled = customProvName.isNotBlank() && customProvUrl.trim().startsWith("http"),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FoundryColors.InkPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create Provider & Configure Key", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (currentProviderInfo?.isCustom == true && !showAddProviderForm) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${currentProviderInfo.baseUrl} (${currentProviderInfo.protocol})",
                        fontSize = 11.sp,
                        color = FoundryColors.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Remove",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FoundryColors.RedInk,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onDeleteCustomProvider(selectedProvider)
                                selectedProvider = "openrouter"
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // STEP 1: API Key -> Query Provider API first
            Text(
                text = "Step 1 · Verify API key & fetch live models",
                style = MaterialTheme.typography.labelMedium,
                color = FoundryColors.InkSecondary,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("$selectedProvider API Key") },
                placeholder = { Text("Paste your API key here") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle key visibility",
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!isFetchingModels && (apiKeyInput.isNotBlank() || currentProviderInfo?.isCustom == true)) {
                        onFetchModels(selectedProvider, apiKeyInput.trim())
                    }
                },
                enabled = !isFetchingModels && (apiKeyInput.isNotBlank() || currentProviderInfo?.isCustom == true),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FoundryColors.InkPrimary),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                if (isFetchingModels) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Querying $selectedProvider API...")
                } else {
                    Text(
                        if (liveModels.isEmpty()) "Verify Key & Load Available Models"
                        else "Refresh Available Models (${liveModels.size})",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = if (statusIsSuccess) FoundryColors.GreenInk else FoundryColors.RedInk,
                )
            }

            // STEP 2: Real Model Picker populated exclusively from the API response
            if (liveModels.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Step 2 · Pick an available model (${liveModels.size} live)",
                    style = MaterialTheme.typography.labelMedium,
                    color = FoundryColors.InkSecondary,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    placeholder = { Text("Filter models (e.g. gemini, claude, gpt, :free)") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(12.dp))
                        .background(FoundryColors.SidebarBg),
                ) {
                    items(filteredModels.take(80), key = { it.id }) { m ->
                        val isChosen = selectedModel == m.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isChosen) Color.White else Color.Transparent)
                                .clickable { selectedModel = m.id }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.id,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                    color = FoundryColors.InkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (m.name != m.id) {
                                    Text(
                                        text = m.name,
                                        fontSize = 11.sp,
                                        color = FoundryColors.InkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (m.isFree) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "FREE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FoundryColors.GreenInk,
                                    modifier = Modifier
                                        .background(FoundryColors.GreenBg, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (isChosen) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = FoundryColors.InkPrimary, modifier = Modifier.size(16.dp))
                            }
                        }
                        HorizontalDivider(color = FoundryColors.Border, thickness = 0.5.dp)
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (selectedModel.isNotBlank() && !isTestingModel) {
                            onSelectAndTestModel(selectedProvider, selectedModel)
                        }
                    },
                    enabled = selectedModel.isNotBlank() && !isTestingModel,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    if (isTestingModel) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Testing model...", color = FoundryColors.InkPrimary)
                    } else {
                        Text(
                            text = if (selectedModel.isBlank()) "Select a model above" else "Save & Activate: $selectedModel",
                            color = FoundryColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))

            Text("Company API Connectors (${connectors.size})", style = MaterialTheme.typography.titleMedium)
            Text(
                "Attach any REST API (Shopify, GitHub, Stripe, WooCommerce...) to the active sandbox.",
                style = MaterialTheme.typography.labelSmall,
                color = FoundryColors.InkSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = connName,
                onValueChange = { connName = it },
                label = { Text("Connector Name (e.g. Shopify, GitHub)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = connUrl,
                onValueChange = { connUrl = it },
                label = { Text("Base URL (https://...)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = connAuth,
                onValueChange = { connAuth = it },
                label = { Text("Authorization Header (Bearer ...)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Live External Calls (off = Dry-Run)", style = MaterialTheme.typography.labelMedium)
                Switch(checked = connLive, onCheckedChange = { connLive = it })
            }
            OutlinedButton(
                onClick = {
                    if (connName.isNotBlank() && connUrl.startsWith("http")) {
                        onAddConnector(connName.trim(), connUrl.trim(), connAuth.trim(), connLive)
                        connName = ""
                        connUrl = ""
                        connAuth = ""
                    }
                },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Attach Connector to Sandbox", color = FoundryColors.InkPrimary)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(FoundryColors.RedBg)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = FoundryColors.RedInk)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Global Kill Switch", fontWeight = FontWeight.Bold, color = FoundryColors.RedInk, fontSize = 13.5.sp)
                        Text("Freeze all bots immediately", fontSize = 11.5.sp, color = FoundryColors.InkSecondary)
                    }
                }
                Switch(checked = killSwitch, onCheckedChange = onToggleKillSwitch)
            }
        }
    }
}
