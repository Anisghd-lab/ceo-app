package com.foundryos.app.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.ArtifactItem
import com.foundryos.app.data.BoardMeetingData
import com.foundryos.app.data.BotItem
import com.foundryos.app.ui.theme.FoundryColors

/**
 * 1. Exact replica of the video's "Your company — 1 human · N bots" inline org card.
 */
@Composable
fun CompanyOrgCard(
    companyName: String,
    subtitle: String,
    bots: List<BotItem>,
    connectors: List<String> = emptyList(),
    workingBotIds: Set<String> = emptySet(),
    onBotClick: ((BotItem) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FoundryColors.CardBg)
            .border(1.dp, FoundryColors.Border, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            text = companyName.ifBlank { "Your company" },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = FoundryColors.InkPrimary,
        )
        Text(
            text = subtitle.ifBlank { "1 human · ${bots.size} bots · isolated sandbox" },
            style = MaterialTheme.typography.labelMedium,
            color = FoundryColors.InkSecondary,
        )
        Spacer(Modifier.height(14.dp))

        bots.forEachIndexed { idx, bot ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = onBotClick != null) { onBotClick?.invoke(bot) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotMascot(shape = bot.avatarShape, colorHex = bot.avatarColor, size = 34.dp, isWorking = bot.id in workingBotIds)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bot.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
                            color = FoundryColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bot.isQa) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "QA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = FoundryColors.GreenInk,
                                modifier = Modifier
                                    .background(FoundryColors.GreenBg, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = bot.tagline.ifBlank { bot.role },
                        style = MaterialTheme.typography.labelMedium,
                        color = FoundryColors.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (bot.allowedTools.isNotEmpty()) {
                    Text(
                        text = "${bot.allowedTools.size} tools",
                        fontSize = 10.5.sp,
                        color = FoundryColors.InkMuted,
                        modifier = Modifier
                            .background(FoundryColors.SoftSurface, RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
            if (idx < bots.lastIndex) {
                Spacer(Modifier.height(2.dp))
            }
        }

        if (connectors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = FoundryColors.Border, thickness = 0.8.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Suggested APIs: ${connectors.joinToString(" · ")}",
                style = MaterialTheme.typography.labelSmall,
                color = FoundryColors.InkSecondary,
            )
        }
    }
}

/**
 * 2. Exact replica of the video's "acme/app #128 / All checks passed in sandbox" deliverable card.
 */
@Composable
fun ArtifactDeliverableCard(
    artifact: ArtifactItem,
    onDownloadClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val passed = artifact.qaStatus.equals("PASSED", ignoreCase = true)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(FoundryColors.CardBg)
            .border(1.dp, FoundryColors.Border, RoundedCornerShape(18.dp)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        tint = FoundryColors.InkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = artifact.filename,
                        style = MaterialTheme.typography.labelMedium,
                        color = FoundryColors.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val badgeText = if (artifact.qaScore != null) "QA ${artifact.qaScore}/100" else artifact.qaStatus
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (passed) FoundryColors.GreenInk else FoundryColors.AmberInk,
                    modifier = Modifier
                        .background(
                            if (passed) FoundryColors.GreenBg else Color(0xFFFFFBEB),
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = artifact.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = FoundryColors.InkPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatBytes(artifact.sizeBytes)} · by ${artifact.botName.ifBlank { "Sandbox Bot" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = FoundryColors.InkSecondary,
                )
                if (onDownloadClick != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(FoundryColors.SoftSurface)
                            .clickable { onDownloadClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download",
                            tint = FoundryColors.InkPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("File", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = FoundryColors.InkPrimary)
                    }
                }
            }
        }

        HorizontalDivider(color = FoundryColors.Border, thickness = 0.8.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (passed) FoundryColors.GreenBg.copy(alpha = 0.45f) else FoundryColors.SoftSurface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (passed) FoundryColors.GreenInk else FoundryColors.InkSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = artifact.qaReport.ifBlank { "All checks passed in isolated sandbox" },
                style = MaterialTheme.typography.labelMedium,
                color = if (passed) FoundryColors.GreenInk else FoundryColors.InkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 3. Exact replica of the video's "🚀 Release — Ship v1.4 to users? [Not yet] [Ship] -> [✓ Shipped]" card.
 */
@Composable
fun ReleaseApprovalCard(
    approval: ApprovalItem,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isShipped = approval.status == "EXECUTED" || approval.status == "APPROVED"
    val isRejected = approval.status == "REJECTED"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(FoundryColors.SidebarBg)
            .border(1.dp, FoundryColors.Border, RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.RocketLaunch,
                contentDescription = null,
                tint = FoundryColors.InkSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Release",
                style = MaterialTheme.typography.labelMedium,
                color = FoundryColors.InkSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(FoundryColors.SoftSurface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RocketLaunch,
                        contentDescription = null,
                        tint = FoundryColors.InkPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = approval.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = FoundryColors.InkPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = approval.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = FoundryColors.InkSecondary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isShipped -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(FoundryColors.GreenSolid)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = "✓ Shipped",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp,
                            )
                        }
                    }
                    isRejected -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(FoundryColors.SoftSurface)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = "Sent back for revision",
                                color = FoundryColors.InkSecondary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(FoundryColors.SoftSurface)
                                .clickable { onReject() }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text = "Not yet",
                                color = FoundryColors.InkPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onApprove,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = FoundryColors.InkPrimary,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("Ship", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 4. Exact replica of the video's "Board meeting" 2x2 KPI card with lavender highlight tile.
 */
@Composable
fun BoardMeetingCard(
    data: BoardMeetingData,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White)
                .border(1.dp, FoundryColors.Border, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = FoundryColors.InkPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${data.sourcesCount} sandbox sources",
                style = MaterialTheme.typography.labelMedium,
                color = FoundryColors.InkSecondary,
            )
            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile(
                    value = data.shipped.toString(),
                    label = "Shipped",
                    modifier = Modifier.weight(1f),
                )
                KpiTile(
                    value = data.missions.toString(),
                    label = "Missions run",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile(
                    value = data.qaPassed.toString(),
                    label = "QA verified",
                    modifier = Modifier.weight(1f),
                )
                KpiTile(
                    value = data.decisionsApproved.toString(),
                    label = "Decisions approved",
                    bgColor = FoundryColors.LavenderBg,
                    valueColor = FoundryColors.LavenderInk,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .border(1.dp, FoundryColors.Border, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Verified,
                    contentDescription = null,
                    tint = FoundryColors.GreenInk,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "You approve what ships",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = FoundryColors.InkPrimary,
                )
            }
        }
    }
}

@Composable
private fun KpiTile(
    value: String,
    label: String,
    bgColor: Color = FoundryColors.SidebarBg,
    valueColor: Color = FoundryColors.InkPrimary,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(14.dp),
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = FoundryColors.InkSecondary,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.2f MB", kb / 1024.0)
}
