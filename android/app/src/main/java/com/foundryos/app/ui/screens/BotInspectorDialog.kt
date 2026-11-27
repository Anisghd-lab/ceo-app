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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foundryos.app.data.BotItem
import com.foundryos.app.data.BotLiveActivity
import com.foundryos.app.ui.components.BotMascot
import com.foundryos.app.ui.theme.FoundryColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Telemetry & Activity Inspector when tapping a Bot:
 * Displays what the bot is currently doing, which Plugin, Tool, and Site URL it's visiting,
 * its current reasoning snippet, and a full timestamped trace of its autonomous actions.
 */
@Composable
fun BotInspectorDialog(
    bot: BotItem,
    allBots: List<BotItem>,
    telemetryMap: Map<String, BotLiveActivity>,
    onSelectBot: (BotItem) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val live = telemetryMap[bot.id]
    val status = live?.status ?: bot.status
    val isWorking = status.equals("working", ignoreCase = true)
    val statusColor = when {
        isWorking -> FoundryColors.GreenInk
        status.equals("error", true) || status.equals("stopped", true) -> FoundryColors.AmberInk
        else -> FoundryColors.InkSecondary
    }
    val statusBg = when {
        isWorking -> FoundryColors.GreenBg
        status.equals("error", true) || status.equals("stopped", true) -> Color(0xFFFFFBEB)
        else -> FoundryColors.SoftSurface
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(24.dp))
                .padding(18.dp),
        ) {
            // Switcher pills across all bots in the active company
            if (allBots.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    allBots.forEach { b ->
                        val active = b.id == bot.id
                        val bWorking = (telemetryMap[b.id]?.status ?: b.status).equals("working", true)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) FoundryColors.InkPrimary else FoundryColors.SidebarBg)
                                .clickable { onSelectBot(b) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (bWorking) FoundryColors.GreenSolid else FoundryColors.InkMuted, CircleShape),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = b.name,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (active) Color.White else FoundryColors.InkPrimary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    BotMascot(shape = bot.avatarShape, colorHex = bot.avatarColor, size = 40.dp, isWorking = isWorking)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(bot.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = status.uppercase(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(statusBg)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            text = "${bot.role} · model: ${bot.model}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FoundryColors.InkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Live Current State Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(FoundryColors.SidebarBg)
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Live Activity & Location", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FoundryColors.InkPrimary)

                    TelemetryRow(
                        icon = Icons.Rounded.Psychology,
                        label = "Task",
                        value = live?.currentTask?.ifBlank { bot.statusText } ?: bot.statusText.ifBlank { "Waiting for mission" },
                    )
                    TelemetryRow(
                        icon = Icons.Rounded.Extension,
                        label = "Plugin",
                        value = live?.currentPlugin?.ifBlank { "Sandbox Core" } ?: "None active",
                    )
                    TelemetryRow(
                        icon = Icons.Rounded.Build,
                        label = "Tool",
                        value = live?.currentTool?.ifBlank { "—" } ?: "—",
                    )

                    val activeUrl = live?.currentUrl.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, contentDescription = null, tint = FoundryColors.InkSecondary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Site:", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
                        Spacer(Modifier.width(6.dp))
                        if (activeUrl.startsWith("http")) {
                            Text(
                                text = activeUrl,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = FoundryColors.BlueInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(FoundryColors.BlueBg)
                                    .clickable { onOpenUrl(activeUrl) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        } else {
                            Text("On-device isolated sandbox", fontSize = 11.5.sp, color = FoundryColors.InkMuted)
                        }
                    }

                    val thought = live?.currentReasoning.orEmpty()
                    if (thought.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, FoundryColors.Border, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                        ) {
                            Text("Latest Reasoning", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = FoundryColors.InkSecondary)
                            Spacer(Modifier.height(3.dp))
                            Text(thought.takeLast(500), fontSize = 11.5.sp, color = FoundryColors.InkPrimary)
                        }
                    }
                }

                // CEO-Assigned Role & Bespoke System Prompt
                if (bot.systemPrompt.isNotBlank()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, FoundryColors.Border, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "CEO Role & Assigned Prompt",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = FoundryColors.InkSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = bot.systemPrompt,
                            fontSize = 11.5.sp,
                            color = FoundryColors.InkPrimary,
                        )
                    }
                }

                // Tools assigned to this bot
                if (bot.allowedTools.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .border(1.dp, FoundryColors.Border, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        Text("Assigned Tools (${bot.allowedTools.size})", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            bot.allowedTools.forEach { t ->
                                Text(
                                    text = t,
                                    fontSize = 11.sp,
                                    color = FoundryColors.InkPrimary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(FoundryColors.SoftSurface)
                                        .padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                // Execution History Trace
                val traces = live?.traces.orEmpty().reversed()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .border(1.dp, FoundryColors.Border, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    Text("Action & Plugin History (${traces.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FoundryColors.InkPrimary)
                    Spacer(Modifier.height(6.dp))
                    if (traces.isEmpty()) {
                        Text(
                            text = "No plugin/tool executions recorded in this session yet. Send a task to CEO or ${bot.name} to observe live steps.",
                            fontSize = 11.5.sp,
                            color = FoundryColors.InkMuted,
                        )
                    } else {
                        traces.forEachIndexed { idx, tr ->
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(tr.timestampMs))
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${tr.plugin} · ${tr.tool}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (tr.ok) FoundryColors.InkPrimary else FoundryColors.RedInk,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(timeStr, fontSize = 10.sp, color = FoundryColors.InkMuted)
                                }
                                if (tr.url.startsWith("http")) {
                                    Text(
                                        text = tr.url,
                                        fontSize = 10.5.sp,
                                        color = FoundryColors.BlueInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.clickable { onOpenUrl(tr.url) },
                                    )
                                }
                                if (tr.summary.isNotBlank()) {
                                    Text(tr.summary.take(180), fontSize = 11.sp, color = FoundryColors.InkSecondary)
                                }
                            }
                            if (idx < traces.lastIndex) {
                                HorizontalDivider(color = FoundryColors.Border, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = FoundryColors.InkSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("$label:", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = FoundryColors.InkSecondary)
        Spacer(Modifier.width(6.dp))
        Text(value, fontSize = 11.5.sp, color = FoundryColors.InkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
