package com.foundryos.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.CompanyProposalData
import com.foundryos.app.data.ConnectorItem
import com.foundryos.app.data.ProviderStatus
import com.foundryos.app.ui.components.ArtifactDeliverableCard
import com.foundryos.app.ui.components.CompanyOrgCard
import com.foundryos.app.ui.theme.FoundryColors

/**
 * 100% Free-form Company Founding Dialog:
 * Describe ANY business -> CEO dynamically designs the bot org-chart -> Deploy isolated sandbox.
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
                Column {
                    Text("Found a new company", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "1 Sandbox = 1 Company · CEO designs the exact team",
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
                    Text("Ex: Create a digital products company that crafts high-quality PDFs, verifies them via QA, and prepares store listings — or any other business...")
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
                Column {
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
    serverUrl: String,
    pairingToken: String,
    connectionStatus: String,
    isLive: Boolean,
    providers: List<ProviderStatus>,
    connectors: List<ConnectorItem>,
    killSwitch: Boolean,
    onSaveConnection: (String, String) -> Unit,
    onSaveProvider: (String, String, String) -> Unit,
    onAddConnector: (String, String, String, Boolean) -> Unit,
    onToggleKillSwitch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var urlInput by remember { mutableStateOf(serverUrl) }
    var tokenInput by remember { mutableStateOf(pairingToken) }
    var selectedProvider by remember { mutableStateOf("openrouter") }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("anthropic/claude-3.5-sonnet") }

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
                Text("CEO Kernel & Keys", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            // Status pill
            Text(
                text = connectionStatus,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isLive) FoundryColors.GreenInk else FoundryColors.AmberInk,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isLive) FoundryColors.GreenBg else Color(0xFFFFFBEB))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Kernel Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Pairing Token (data/pairing.token)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSaveConnection(urlInput, tokenInput) },
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FoundryColors.InkPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect to Kernel")
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))

            Text("Multi-LLM Router", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("openrouter", "gemini", "xai", "openai", "anthropic").forEach { prov ->
                    val active = selectedProvider == prov
                    val configured = providers.any { it.name == prov && it.configured }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (active) FoundryColors.InkPrimary else FoundryColors.SoftSurface)
                            .clickable {
                                selectedProvider = prov
                                modelInput = providers.firstOrNull { it.name == prov }?.model ?: modelInput
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (configured) "$prov ✓" else prov,
                            fontSize = 11.sp,
                            color = if (active) Color.White else FoundryColors.InkPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("$selectedProvider API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("Default Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (apiKeyInput.isNotBlank()) {
                        onSaveProvider(selectedProvider, apiKeyInput.trim(), modelInput.trim())
                        apiKeyInput = ""
                    }
                },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save LLM Key", color = FoundryColors.InkPrimary)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = FoundryColors.Border)
            Spacer(Modifier.height(12.dp))

            Text("Company API Connectors (${connectors.size})", style = MaterialTheme.typography.titleMedium)
            Text(
                "Attach any REST API (Shopify, GitHub, Stripe, Twenty, Dify...) to the active sandbox.",
                style = MaterialTheme.typography.labelSmall,
                color = FoundryColors.InkSecondary,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = connName,
                onValueChange = { connName = it },
                label = { Text("Connector Name (e.g. Shopify, GitHub)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = connUrl,
                onValueChange = { connUrl = it },
                label = { Text("Base URL (https://...)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = connAuth,
                onValueChange = { connAuth = it },
                label = { Text("Authorization Header Value (Bearer ...)") },
                singleLine = true,
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

            // Global Kill Switch
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
