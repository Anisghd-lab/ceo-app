package com.foundryos.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun parseHexColor(hex: String, fallback: Color = Color(0xFF111111)): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val value = cleaned.toLong(16)
        if (cleaned.length == 6) Color(0xFF000000 or value) else Color(value)
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Exact geometric mascot icons from the reference video:
 * solid geometric shape + 2 crisp white vertical pill-eyes looking slightly right.
 * Animates ONLY when `isWorking == true` (active execution / reasoning).
 */
@Composable
fun BotMascot(
    shape: String,
    colorHex: String,
    size: Dp = 36.dp,
    badgeCount: Int = 0,
    isWorking: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val fill = parseHexColor(colorHex)

    val workingTransition = if (isWorking) rememberInfiniteTransition(label = "bot_working") else null
    val pulseScale = workingTransition?.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 680, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )?.value ?: 1f
    val tiltDeg = workingTransition?.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tiltDeg",
    )?.value ?: 0f
    val bobY = workingTransition?.animateFloat(
        initialValue = -2.2f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 740, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bobY",
    )?.value ?: 0f
    val auraProgress = workingTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "auraProgress",
    )?.value ?: 0f
    val eyeShift = workingTransition?.animateFloat(
        initialValue = -0.025f,
        targetValue = 0.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eyeShift",
    )?.value ?: 0f

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (isWorking) {
            Canvas(modifier = Modifier.size(size)) {
                val auraColor = if (fill == Color(0xFF111111)) Color(0xFF10B981) else fill
                val maxR = this.size.minDimension * 0.62f
                val minR = this.size.minDimension * 0.44f
                val curR = minR + (maxR - minR) * auraProgress
                drawCircle(
                    color = auraColor.copy(alpha = (1f - auraProgress) * 0.55f),
                    radius = curR,
                    style = Stroke(width = 2.2.dp.toPx()),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    if (isWorking) {
                        scaleX = pulseScale
                        scaleY = pulseScale
                        rotationZ = tiltDeg
                        translationY = bobY
                    }
                },
        ) {
            val w = this.size.width
            val h = this.size.height
            when (shape.lowercase()) {
                "triangle" -> drawRoundedTriangle(fill, w, h)
                "hexagon" -> drawHexagon(fill, w, h)
                "pill" -> drawRoundRect(
                    color = fill,
                    topLeft = Offset(w * 0.06f, h * 0.18f),
                    size = Size(w * 0.88f, h * 0.64f),
                    cornerRadius = CornerRadius(h * 0.34f, h * 0.34f),
                )
                "semicircle" -> drawCircle(
                    color = fill,
                    radius = w * 0.44f,
                    center = Offset(w * 0.5f, h * 0.52f),
                )
                "squircle" -> drawRoundRect(
                    color = fill,
                    topLeft = Offset(w * 0.10f, h * 0.10f),
                    size = Size(w * 0.80f, h * 0.80f),
                    cornerRadius = CornerRadius(w * 0.28f, w * 0.28f),
                )
                "diamond" -> rotate(45f, pivot = Offset(w / 2f, h / 2f)) {
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(w * 0.20f, h * 0.20f),
                        size = Size(w * 0.60f, h * 0.60f),
                        cornerRadius = CornerRadius(w * 0.16f, w * 0.16f),
                    )
                }
                else -> drawCircle(color = fill, radius = w * 0.45f)
            }

            // Signature pair of white pill eyes (identical to the video mascots)
            val eyeW = w * 0.085f
            val eyeH = h * 0.22f
            val eyeRadius = CornerRadius(eyeW / 2f, eyeW / 2f)
            val yOffset = if (shape.lowercase() == "triangle") h * 0.49f else h * 0.36f
            val leftX = (if (shape.lowercase() == "circle") w * 0.50f else w * 0.39f) + (w * eyeShift)
            val rightX = leftX + w * 0.15f

            rotate(degrees = if (shape.lowercase() == "circle") 8f else 0f, pivot = Offset(w / 2f, h / 2f)) {
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(leftX, yOffset),
                    size = Size(eyeW, eyeH),
                    cornerRadius = eyeRadius,
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(rightX, yOffset + h * 0.02f),
                    size = Size(eyeW, eyeH * 0.90f),
                    cornerRadius = eyeRadius,
                )
            }
        }

        if (badgeCount > 0) {
            val badgeBg = if (fill == Color(0xFF111111)) Color(0xFFEF4444) else fill
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-3).dp)
                    .size(18.dp)
                    .background(badgeBg, CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 0.5.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawRoundedTriangle(color: Color, w: Float, h: Float) {
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.10f)
        lineTo(w * 0.90f, h * 0.84f)
        quadraticTo(w * 0.92f, h * 0.90f, w * 0.84f, h * 0.90f)
        lineTo(w * 0.16f, h * 0.90f)
        quadraticTo(w * 0.08f, h * 0.90f, w * 0.10f, h * 0.84f)
        close()
    }
    drawPath(path, color = color)
}

private fun DrawScope.drawHexagon(color: Color, w: Float, h: Float) {
    val path = Path().apply {
        moveTo(w * 0.50f, h * 0.08f)
        lineTo(w * 0.88f, h * 0.29f)
        lineTo(w * 0.88f, h * 0.71f)
        lineTo(w * 0.50f, h * 0.92f)
        lineTo(w * 0.12f, h * 0.71f)
        lineTo(w * 0.12f, h * 0.29f)
        close()
    }
    drawPath(path, color = color)
}
