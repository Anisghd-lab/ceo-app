package com.foundryos.app.engine

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

data class PdfVisualItem(
    val label: String,
    val value: Float = 0f,
    val detail: String = "",
)

data class PdfIllustrationSpec(
    val type: String = "framework", // bar_chart | roadmap | kpi_grid | framework | habit_tracker | checklist_card
    val title: String = "",
    val items: List<PdfVisualItem> = emptyList(),
)

data class PdfBookChapter(
    val heading: String,
    val body: String,
    val summary: String = "",
    val illustration: PdfIllustrationSpec? = null,
)

data class PdfBookSpec(
    val title: String,
    val subtitle: String = "",
    val author: String = "",
    val editionBadge: String = "COMPLETE EDITION",
    val theme: String = "executive", // executive | emerald | editorial | minimal | wellness
    val highlights: List<String> = emptyList(),
    val chapters: List<PdfBookChapter> = emptyList(),
)

/**
 * Publication-grade on-device Android PDF Book & Workbook Renderer.
 * Renders full-bleed themed cover pages with vector artwork, Table of Contents,
 * styled chapter headers, rich markdown (bold, subheadings, checklists, bullets,
 * callouts, tables, fill-in worksheet lines), native vector charts/diagrams,
 * and running headers/footers with page numbers.
 */
object PdfBookRenderer {

    private data class BookPalette(
        val coverBg: Int,
        val coverAccent: Int,
        val coverAccentSoft: Int,
        val coverText: Int,
        val coverSubText: Int,
        val primary: Int,
        val accent: Int,
        val accentTint: Int,
        val ink: Int,
        val inkMuted: Int,
        val border: Int,
        val surface: Int,
        val calloutBg: Int,
    )

    private fun paletteFor(theme: String): BookPalette = when (theme.trim().lowercase()) {
        "emerald", "finance", "growth", "green" -> BookPalette(
            coverBg = Color.parseColor("#0B2E26"),
            coverAccent = Color.parseColor("#10B981"),
            coverAccentSoft = Color.parseColor("#134E41"),
            coverText = Color.parseColor("#FFFFFF"),
            coverSubText = Color.parseColor("#A7F3D0"),
            primary = Color.parseColor("#065F46"),
            accent = Color.parseColor("#10B981"),
            accentTint = Color.parseColor("#ECFDF5"),
            ink = Color.parseColor("#111827"),
            inkMuted = Color.parseColor("#6B7280"),
            border = Color.parseColor("#D1FAE5"),
            surface = Color.parseColor("#F8FAFC"),
            calloutBg = Color.parseColor("#ECFDF5"),
        )
        "editorial", "warm", "lifestyle", "terracotta", "brand" -> BookPalette(
            coverBg = Color.parseColor("#1C1917"),
            coverAccent = Color.parseColor("#EA580C"),
            coverAccentSoft = Color.parseColor("#332924"),
            coverText = Color.parseColor("#FFFFFF"),
            coverSubText = Color.parseColor("#FED7AA"),
            primary = Color.parseColor("#9A3412"),
            accent = Color.parseColor("#EA580C"),
            accentTint = Color.parseColor("#FFF7ED"),
            ink = Color.parseColor("#1C1917"),
            inkMuted = Color.parseColor("#78716C"),
            border = Color.parseColor("#E7E5E4"),
            surface = Color.parseColor("#FAFAF9"),
            calloutBg = Color.parseColor("#FFF7ED"),
        )
        "wellness", "teal", "planner", "reset", "life" -> BookPalette(
            coverBg = Color.parseColor("#102A43"),
            coverAccent = Color.parseColor("#14B8A6"),
            coverAccentSoft = Color.parseColor("#1E3E5C"),
            coverText = Color.parseColor("#FFFFFF"),
            coverSubText = Color.parseColor("#99F6E4"),
            primary = Color.parseColor("#0F766E"),
            accent = Color.parseColor("#14B8A6"),
            accentTint = Color.parseColor("#F0FDFA"),
            ink = Color.parseColor("#0F172A"),
            inkMuted = Color.parseColor("#64748B"),
            border = Color.parseColor("#CBD5E1"),
            surface = Color.parseColor("#F8FAFC"),
            calloutBg = Color.parseColor("#F0FDFA"),
        )
        "minimal", "tech", "saas", "indigo" -> BookPalette(
            coverBg = Color.parseColor("#111827"),
            coverAccent = Color.parseColor("#6366F1"),
            coverAccentSoft = Color.parseColor("#1F2937"),
            coverText = Color.parseColor("#FFFFFF"),
            coverSubText = Color.parseColor("#C7D2FE"),
            primary = Color.parseColor("#4338CA"),
            accent = Color.parseColor("#6366F1"),
            accentTint = Color.parseColor("#EEF2FF"),
            ink = Color.parseColor("#111827"),
            inkMuted = Color.parseColor("#6B7280"),
            border = Color.parseColor("#E5E7EB"),
            surface = Color.parseColor("#F9FAFB"),
            calloutBg = Color.parseColor("#EEF2FF"),
        )
        else -> BookPalette( // "executive" Navy & Gold
            coverBg = Color.parseColor("#0F172A"),
            coverAccent = Color.parseColor("#D97706"),
            coverAccentSoft = Color.parseColor("#1E293B"),
            coverText = Color.parseColor("#FFFFFF"),
            coverSubText = Color.parseColor("#FDE68A"),
            primary = Color.parseColor("#0F172A"),
            accent = Color.parseColor("#D97706"),
            accentTint = Color.parseColor("#FFFBEB"),
            ink = Color.parseColor("#111827"),
            inkMuted = Color.parseColor("#64748B"),
            border = Color.parseColor("#E2E8F0"),
            surface = Color.parseColor("#F8FAFC"),
            calloutBg = Color.parseColor("#FFFBEB"),
        )
    }

    fun inferTheme(title: String, subtitle: String): String {
        val sig = "$title $subtitle".lowercase()
        return when {
            listOf("reset", "planner", "life", "habit", "wellness", "journal", "mindset").any { it in sig } -> "wellness"
            listOf("finance", "budget", "saving", "wealth", "invest", "money", "revenue").any { it in sig } -> "emerald"
            listOf("design", "brand", "creative", "marketing", "social", "story", "ebook", "recipe").any { it in sig } -> "editorial"
            listOf("ai", "tech", "code", "saas", "seo", "software", "app", "automation").any { it in sig } -> "minimal"
            else -> "executive"
        }
    }

    /**
     * Parses raw markdown prose (with # / ## chapter headings) into structured chapters
     * so even plain-text LLM book drafts render as full illustrated multi-chapter books.
     */
    fun parseMarkdownIntoBook(
        title: String,
        subtitle: String,
        author: String,
        markdown: String,
        theme: String = inferTheme(title, subtitle),
    ): PdfBookSpec {
        val cleaned = markdown.trim()
        val chapters = mutableListOf<PdfBookChapter>()
        var currentHeading = ""
        val currentBody = StringBuilder()

        fun flushChapter() {
            val bodyText = currentBody.toString().trim()
            if (currentHeading.isNotBlank() || bodyText.isNotBlank()) {
                val heading = currentHeading.ifBlank { "Overview & Framework" }
                chapters.add(
                    PdfBookChapter(
                        heading = heading,
                        body = bodyText.ifBlank { "Key action steps and insights for $heading." },
                        illustration = autoSynthesizeIllustration(heading, bodyText),
                    )
                )
            }
            currentHeading = ""
            currentBody.clear()
        }

        val lines = cleaned.lines()
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val h12 = Regex("""^#{1,2}\s+(.+)$""").matchEntire(line.trim())
            if (h12 != null) {
                val candidate = h12.groupValues[1].replace("**", "").trim()
                // Skip duplicating the exact main title at line 1
                if (chapters.isEmpty() && currentHeading.isBlank() && currentBody.isBlank() &&
                    candidate.equals(title.trim(), ignoreCase = true)
                ) {
                    continue
                }
                flushChapter()
                currentHeading = candidate
            } else {
                currentBody.append(line).append('\n')
            }
        }
        flushChapter()

        val finalChapters = if (chapters.isEmpty()) {
            listOf(
                PdfBookChapter(
                    heading = "Complete Guide",
                    body = cleaned,
                    illustration = autoSynthesizeIllustration(title, cleaned),
                )
            )
        } else chapters

        val highlights = finalChapters.take(4).map { it.heading.take(48) }
        return PdfBookSpec(
            title = title,
            subtitle = subtitle,
            author = author,
            theme = theme,
            highlights = highlights,
            chapters = finalChapters,
        )
    }

    /**
     * Builds a relevant vector diagram/chart spec from a chapter's content if none was explicitly supplied.
     */
    fun autoSynthesizeIllustration(heading: String, body: String): PdfIllustrationSpec? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return null

        // Check for percentage / numeric items -> bar_chart
        val metricItems = mutableListOf<PdfVisualItem>()
        val pctRegex = Regex("""(\d{1,3})\s*%""")
        for (line in lines) {
            val m = pctRegex.find(line) ?: continue
            val pct = m.groupValues[1].toFloatOrNull()?.coerceIn(5f, 100f) ?: continue
            val label = line.replace(Regex("""^[-•*\d.)\[\]\sxX]+"""), "")
                .replace("**", "")
                .substringBefore(":")
                .substringBefore("—")
                .trim()
                .take(36)
            if (label.length >= 3) {
                metricItems.add(PdfVisualItem(label = label, value = pct, detail = "${pct.toInt()}%"))
            }
            if (metricItems.size >= 5) break
        }
        if (metricItems.size >= 2) {
            return PdfIllustrationSpec(
                type = "bar_chart",
                title = "${heading.take(42)} — Key Metrics",
                items = metricItems,
            )
        }

        // Check for checklist / habit items -> habit_tracker or checklist_card
        val checkItems = lines.filter {
            it.startsWith("- [") || it.startsWith("☐") || it.startsWith("✓")
        }.take(5).map {
            val clean = it.replace(Regex("""^(-\s*\[[ xX]?\]|☐|✓)\s*"""), "").replace("**", "").trim()
            PdfVisualItem(label = clean.take(54))
        }
        if (checkItems.size >= 3) {
            val isHabit = heading.lowercase().let { "habit" in it || "90" in it || "daily" in it || "routine" in it || "tracker" in it }
            return PdfIllustrationSpec(
                type = if (isHabit) "habit_tracker" else "checklist_card",
                title = if (isHabit) "Weekly Habit Tracker" else "Action Checklist",
                items = checkItems,
            )
        }

        // Check for numbered steps or bullet pillars -> roadmap / framework diagram
        val bulletItems = lines.filter {
            it.matches(Regex("""^(\d+[.)]|[-•*])\s+.+"""))
        }.take(4).map { raw ->
            val clean = raw.replace(Regex("""^(\d+[.)]|[-•*])\s+"""), "").replace("**", "").trim()
            val parts = clean.split(Regex("""\s*[—–:]\s*"""), limit = 2)
            PdfVisualItem(
                label = parts[0].trim().take(30),
                detail = parts.getOrNull(1)?.trim()?.take(56).orEmpty(),
            )
        }
        if (bulletItems.size >= 3) {
            val isSteps = heading.lowercase().let {
                "step" in it || "plan" in it || "roadmap" in it || "quarter" in it || "launch" in it || "phase" in it || "start" in it
            }
            return PdfIllustrationSpec(
                type = if (isSteps) "roadmap" else "framework",
                title = "${heading.take(40)} — Visual Blueprint",
                items = bulletItems,
            )
        }

        return null
    }

    /**
     * Renders the complete illustrated PDF book into [outFile].
     * Returns true if native Android PdfDocument succeeded cleanly, false to trigger ASCII fallback.
     */
    fun renderToFile(outFile: File, spec: PdfBookSpec): Boolean {
        val pageWidth = 595 // A4 points (72 dpi)
        val pageHeight = 842
        val margin = 46f
        val contentWidth = (pageWidth - margin * 2).toInt()
        val palette = paletteFor(spec.theme.ifBlank { inferTheme(spec.title, spec.subtitle) })

        return runCatching {
            val pdf = PdfDocument()
            var pageNum = 0
            var currentPage: PdfDocument.Page? = null
            var canvas: Canvas? = null

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

            val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            val sansNormal = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            val sansItalic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)

            val h1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.primary
                textSize = 20f
                typeface = serifBold
            }
            val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.primary
                textSize = 13.5f
                typeface = sansBold
            }
            val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.ink
                textSize = 10.6f
                typeface = sansNormal
            }
            val mutedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.inkMuted
                textSize = 9.5f
                typeface = sansNormal
            }
            val eyebrowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accent
                textSize = 9.5f
                typeface = sansBold
                letterSpacing = 0.12f
            }
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.ink
                textSize = 10.4f
                typeface = sansItalic
            }

            fun finishCurrentPage() {
                currentPage?.let { pdf.finishPage(it) }
                currentPage = null
                canvas = null
            }

            fun startNewPage(withChrome: Boolean, runningSection: String = ""): Boolean {
                finishCurrentPage()
                pageNum += 1
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                    ?: return false
                currentPage = p
                val c = p.canvas
                canvas = c

                if (withChrome) {
                    // Subtle running header
                    fillPaint.color = Color.WHITE
                    c.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), fillPaint)

                    // Thin top accent bar
                    fillPaint.color = palette.accent
                    c.drawRect(0f, 0f, pageWidth.toFloat(), 4f, fillPaint)

                    val hdrLeft = spec.title.take(46)
                    val hdrRight = runningSection.take(36)
                    val hdrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.inkMuted
                        textSize = 8.5f
                        typeface = sansNormal
                    }
                    c.drawText(hdrLeft, margin, 26f, hdrPaint)
                    if (hdrRight.isNotBlank()) {
                        val rw = hdrPaint.measureText(hdrRight)
                        c.drawText(hdrRight, pageWidth - margin - rw, 26f, hdrPaint)
                    }
                    strokePaint.color = palette.border
                    strokePaint.strokeWidth = 0.8f
                    c.drawLine(margin, 33f, pageWidth - margin, 33f, strokePaint)

                    // Running footer with page number
                    c.drawLine(margin, pageHeight - 38f, pageWidth - margin, pageHeight - 38f, strokePaint)
                    val brandText = spec.author.ifBlank { "Editorial Edition" }.take(44)
                    c.drawText(brandText, margin, pageHeight - 22f, hdrPaint)
                    val pageLabel = "Page $pageNum"
                    val pw = hdrPaint.measureText(pageLabel)
                    c.drawText(pageLabel, pageWidth - margin - pw, pageHeight - 22f, hdrPaint)
                }
                return true
            }

            fun makeSpannable(raw: String): CharSequence {
                val sb = SpannableStringBuilder()
                val regex = Regex("""\*\*(.+?)\*\*""")
                var lastEnd = 0
                for (m in regex.findAll(raw)) {
                    if (m.range.first > lastEnd) {
                        sb.append(raw.substring(lastEnd, m.range.first))
                    }
                    val start = sb.length
                    sb.append(m.groupValues[1])
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    lastEnd = m.range.last + 1
                }
                if (lastEnd < raw.length) {
                    sb.append(raw.substring(lastEnd))
                }
                return sb
            }

            fun buildLayout(text: CharSequence, paint: TextPaint, widthPx: Int, spacingMult: Float = 1.24f): StaticLayout {
                return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(60, widthPx))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2.5f, spacingMult)
                    .build()
            }

            val topContentY = 52f
            val bottomLimitY = pageHeight - 54f

            // ==================== PAGE 1: FULL-BLEED EDITORIAL COVER ====================
            if (!startNewPage(withChrome = false)) {
                runCatching { pdf.close() }
                return false
            }
            run {
                val c = canvas!!
                // Full dark rich backdrop
                fillPaint.color = palette.coverBg
                c.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), fillPaint)

                // Decorative editorial vector artwork (top-right geometric rings & ribbons)
                fillPaint.color = palette.coverAccentSoft
                c.drawCircle(pageWidth - 40f, 130f, 185f, fillPaint)
                fillPaint.color = palette.coverAccent
                fillPaint.alpha = 46
                c.drawCircle(pageWidth - 20f, 145f, 120f, fillPaint)
                fillPaint.alpha = 255

                // Bottom architectural diagonal wave / bar
                val wavePath = Path().apply {
                    moveTo(0f, pageHeight - 170f)
                    lineTo(pageWidth.toFloat(), pageHeight - 230f)
                    lineTo(pageWidth.toFloat(), pageHeight.toFloat())
                    lineTo(0f, pageHeight.toFloat())
                    close()
                }
                fillPaint.color = palette.coverAccentSoft
                c.drawPath(wavePath, fillPaint)

                // Left vertical accent stripe
                fillPaint.color = palette.coverAccent
                c.drawRect(0f, 0f, 14f, pageHeight.toFloat(), fillPaint)

                // Edition eyebrow pill
                val badgeText = spec.editionBadge.ifBlank { "COMPLETE EDITION" }.uppercase().take(42)
                val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.coverBg
                    textSize = 9.5f
                    typeface = sansBold
                    letterSpacing = 0.12f
                }
                val badgeW = badgePaint.measureText(badgeText) + 28f
                fillPaint.color = palette.coverAccent
                c.drawRoundRect(RectF(margin, 86f, margin + badgeW, 112f), 13f, 13f, fillPaint)
                c.drawText(badgeText, margin + 14f, 102.5f, badgePaint)

                // Huge editorial Cover Title
                val coverTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.coverText
                    textSize = 34f
                    typeface = serifBold
                }
                val titleLayout = buildLayout(spec.title, coverTitlePaint, contentWidth - 16, 1.15f)
                var cy = 154f
                c.save()
                c.translate(margin, cy)
                titleLayout.draw(c)
                c.restore()
                cy += titleLayout.height + 18f

                // Accent rule under title
                fillPaint.color = palette.coverAccent
                c.drawRoundRect(RectF(margin, cy, margin + 88f, cy + 5f), 2.5f, 2.5f, fillPaint)
                cy += 24f

                // Subtitle
                if (spec.subtitle.isNotBlank()) {
                    val coverSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.coverSubText
                        textSize = 14.5f
                        typeface = sansNormal
                    }
                    val subLayout = buildLayout(spec.subtitle, coverSubPaint, contentWidth - 20, 1.3f)
                    c.save()
                    c.translate(margin, cy)
                    subLayout.draw(c)
                    c.restore()
                    cy += subLayout.height + 28f
                }

                // Visual Cover Emblem / Card showcasing inside highlights
                val highlights = spec.highlights.ifEmpty { spec.chapters.take(4).map { it.heading } }
                if (highlights.isNotEmpty()) {
                    val cardTop = max(cy + 14f, 430f)
                    val cardBottom = min(cardTop + 44f + highlights.size * 34f, pageHeight - 130f)
                    fillPaint.color = Color.argb(38, 255, 255, 255)
                    c.drawRoundRect(RectF(margin, cardTop, pageWidth - margin, cardBottom), 14f, 14f, fillPaint)
                    strokePaint.color = Color.argb(70, 255, 255, 255)
                    strokePaint.strokeWidth = 1f
                    c.drawRoundRect(RectF(margin, cardTop, pageWidth - margin, cardBottom), 14f, 14f, strokePaint)

                    val insideHdr = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.coverAccent
                        textSize = 9.5f
                        typeface = sansBold
                        letterSpacing = 0.14f
                    }
                    c.drawText("INSIDE THIS EDITION", margin + 18f, cardTop + 26f, insideHdr)

                    val itemPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.coverText
                        textSize = 11.5f
                        typeface = sansNormal
                    }
                    var hy = cardTop + 52f
                    highlights.take(5).forEachIndexed { idx, hl ->
                        if (hy < cardBottom - 10f) {
                            fillPaint.color = palette.coverAccent
                            c.drawCircle(margin + 24f, hy - 4f, 4f, fillPaint)
                            c.drawText("${idx + 1}.  ${hl.take(54)}", margin + 38f, hy, itemPaint)
                            hy += 30f
                        }
                    }
                }

                // Bottom Imprint / Brand
                val imprintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.coverSubText
                    textSize = 11f
                    typeface = sansBold
                }
                val imprint = spec.author.ifBlank { "Published Edition" }
                c.drawText(imprint.take(56), margin, pageHeight - 62f, imprintPaint)
                val chapterCountLabel = "${spec.chapters.size} CHAPTERS · PRINT & DIGITAL READY"
                val smallImprintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(180, 255, 255, 255)
                    textSize = 9f
                    typeface = sansNormal
                    letterSpacing = 0.08f
                }
                c.drawText(chapterCountLabel, margin, pageHeight - 42f, smallImprintPaint)
            }

            // ==================== PAGE 2: TABLE OF CONTENTS (if >= 2 chapters) ====================
            if (spec.chapters.size >= 2) {
                startNewPage(withChrome = true, runningSection = "Table of Contents")
                val c = canvas!!
                var y = topContentY + 12f
                c.drawText("CONTENTS", margin, y, eyebrowPaint)
                y += 12f
                val tocTitle = buildLayout("Table of Contents", h1Paint, contentWidth)
                c.save()
                c.translate(margin, y)
                tocTitle.draw(c)
                c.restore()
                y += tocTitle.height + 16f

                fillPaint.color = palette.accent
                c.drawRoundRect(RectF(margin, y, margin + 56f, y + 3.5f), 2f, 2f, fillPaint)
                y += 22f

                spec.chapters.forEachIndexed { idx, ch ->
                    if (y > bottomLimitY - 56f) {
                        startNewPage(withChrome = true, runningSection = "Table of Contents")
                        y = topContentY + 10f
                    }
                    val curCanvas = canvas!!
                    // Row card
                    val rowH = if (ch.summary.isNotBlank()) 52f else 38f
                    fillPaint.color = if (idx % 2 == 0) palette.surface else Color.WHITE
                    curCanvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + rowH), 10f, 10f, fillPaint)
                    strokePaint.color = palette.border
                    strokePaint.strokeWidth = 0.8f
                    curCanvas.drawRoundRect(RectF(margin, y, pageWidth - margin, y + rowH), 10f, 10f, strokePaint)

                    // Number badge
                    fillPaint.color = palette.accent
                    curCanvas.drawRoundRect(RectF(margin + 10f, y + 9f, margin + 36f, y + 29f), 6f, 6f, fillPaint)
                    val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 10f
                        typeface = sansBold
                    }
                    val numStr = String.format("%02d", idx + 1)
                    curCanvas.drawText(numStr, margin + 17f, y + 22.5f, numPaint)

                    val chTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.ink
                        textSize = 11.5f
                        typeface = sansBold
                    }
                    curCanvas.drawText(ch.heading.take(62), margin + 48f, y + 23f, chTitlePaint)
                    if (ch.summary.isNotBlank()) {
                        curCanvas.drawText(ch.summary.take(78), margin + 48f, y + 40f, mutedPaint)
                    }
                    y += rowH + 8f
                }
            }

            // ==================== CHAPTER PAGES ====================
            fun drawIllustrationBlock(
                specVis: PdfIllustrationSpec,
                curY: Float,
                chapterHeading: String,
            ): Float {
                val items = specVis.items.take(6)
                if (items.isEmpty()) return curY
                val estHeight = when (specVis.type.lowercase()) {
                    "bar_chart" -> 54f + items.size * 30f
                    "kpi_grid" -> 56f + ((items.size + 1) / 2) * 62f
                    "roadmap" -> 60f + items.size * 42f
                    "habit_tracker" -> 66f + items.size * 26f
                    "checklist_card" -> 54f + items.size * 26f
                    else -> 58f + ((items.size + 1) / 2) * 58f // framework cards
                }
                var y = curY
                if (y + estHeight > bottomLimitY) {
                    startNewPage(withChrome = true, runningSection = chapterHeading)
                    y = topContentY
                }
                val c = canvas!!
                val boxRect = RectF(margin, y, pageWidth - margin, y + estHeight)
                fillPaint.color = palette.surface
                c.drawRoundRect(boxRect, 12f, 12f, fillPaint)
                strokePaint.color = palette.border
                strokePaint.strokeWidth = 1f
                c.drawRoundRect(boxRect, 12f, 12f, strokePaint)

                // Top-left visual badge + title
                fillPaint.color = palette.accent
                c.drawRoundRect(RectF(margin + 14f, y + 14f, margin + 20f, y + 28f), 3f, 3f, fillPaint)
                val visTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primary
                    textSize = 11f
                    typeface = sansBold
                }
                c.drawText(specVis.title.ifBlank { "Visual Diagram" }.take(64), margin + 28f, y + 25f, visTitlePaint)

                var iy = y + 44f
                val innerLeft = margin + 16f
                val innerRight = pageWidth - margin - 16f
                val innerW = innerRight - innerLeft

                when (specVis.type.lowercase()) {
                    "bar_chart" -> {
                        val maxVal = items.maxOfOrNull { it.value }.takeIf { it != null && it > 0f } ?: 100f
                        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = palette.ink
                            textSize = 9.5f
                            typeface = sansBold
                        }
                        for (item in items) {
                            val pct = if (item.value > 0f) (item.value / maxVal).coerceIn(0.08f, 1f) else 0.55f
                            c.drawText(item.label.take(36), innerLeft, iy + 9f, labelPaint)
                            val valTxt = item.detail.ifBlank { "${item.value.toInt()}" }
                            val vw = mutedPaint.measureText(valTxt)
                            c.drawText(valTxt, innerRight - vw, iy + 9f, mutedPaint)

                            val barTop = iy + 13f
                            fillPaint.color = palette.border
                            c.drawRoundRect(RectF(innerLeft, barTop, innerRight, barTop + 8f), 4f, 4f, fillPaint)
                            fillPaint.color = palette.accent
                            c.drawRoundRect(RectF(innerLeft, barTop, innerLeft + innerW * pct, barTop + 8f), 4f, 4f, fillPaint)
                            iy += 30f
                        }
                    }
                    "roadmap" -> {
                        val nodePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            textSize = 9.5f
                            typeface = sansBold
                        }
                        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = palette.ink
                            textSize = 10.2f
                            typeface = sansBold
                        }
                        items.forEachIndexed { idx, item ->
                            val cx = innerLeft + 12f
                            val cyNode = iy + 12f
                            if (idx < items.lastIndex) {
                                strokePaint.color = palette.accent
                                strokePaint.strokeWidth = 2f
                                c.drawLine(cx, cyNode + 10f, cx, cyNode + 40f, strokePaint)
                            }
                            fillPaint.color = palette.accent
                            c.drawCircle(cx, cyNode, 10f, fillPaint)
                            c.drawText("${idx + 1}", cx - 3f, cyNode + 3.5f, nodePaint)
                            c.drawText(item.label.take(54), innerLeft + 32f, iy + 11f, titleP)
                            if (item.detail.isNotBlank()) {
                                c.drawText(item.detail.take(68), innerLeft + 32f, iy + 25f, mutedPaint)
                            }
                            iy += 42f
                        }
                    }
                    "habit_tracker" -> {
                        val days = listOf("M", "T", "W", "T", "F", "S", "S")
                        val gridStartX = innerRight - days.size * 22f
                        days.forEachIndexed { dIdx, d ->
                            c.drawText(d, gridStartX + dIdx * 22f + 5f, iy + 4f, mutedPaint)
                        }
                        iy += 14f
                        val labelP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = palette.ink
                            textSize = 9.8f
                            typeface = sansNormal
                        }
                        for (item in items) {
                            c.drawText(item.label.take(36), innerLeft, iy + 11f, labelP)
                            days.forEachIndexed { dIdx, _ ->
                                val bx = gridStartX + dIdx * 22f
                                fillPaint.color = Color.WHITE
                                c.drawRoundRect(RectF(bx, iy, bx + 15f, iy + 15f), 3.5f, 3.5f, fillPaint)
                                strokePaint.color = palette.border
                                strokePaint.strokeWidth = 1f
                                c.drawRoundRect(RectF(bx, iy, bx + 15f, iy + 15f), 3.5f, 3.5f, strokePaint)
                            }
                            iy += 26f
                        }
                    }
                    "checklist_card" -> {
                        val labelP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = palette.ink
                            textSize = 10f
                            typeface = sansNormal
                        }
                        for (item in items) {
                            fillPaint.color = Color.WHITE
                            c.drawRoundRect(RectF(innerLeft, iy, innerLeft + 13f, iy + 13f), 3f, 3f, fillPaint)
                            strokePaint.color = palette.accent
                            strokePaint.strokeWidth = 1.2f
                            c.drawRoundRect(RectF(innerLeft, iy, innerLeft + 13f, iy + 13f), 3f, 3f, strokePaint)
                            c.drawText(item.label.take(66), innerLeft + 22f, iy + 10.5f, labelP)
                            iy += 26f
                        }
                    }
                    else -> { // kpi_grid or framework 2-column visual cards
                        val colGap = 10f
                        val colW = (innerW - colGap) / 2f
                        val cardH = if (specVis.type.equals("kpi_grid", true)) 52f else 48f
                        val cardTitleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = palette.primary
                            textSize = if (specVis.type.equals("kpi_grid", true)) 14f else 10.5f
                            typeface = sansBold
                        }
                        items.forEachIndexed { idx, item ->
                            val col = idx % 2
                            val row = idx / 2
                            val cx = innerLeft + col * (colW + colGap)
                            val cyCard = iy + row * (cardH + 10f)
                            fillPaint.color = Color.WHITE
                            c.drawRoundRect(RectF(cx, cyCard, cx + colW, cyCard + cardH), 9f, 9f, fillPaint)
                            strokePaint.color = palette.border
                            strokePaint.strokeWidth = 0.9f
                            c.drawRoundRect(RectF(cx, cyCard, cx + colW, cyCard + cardH), 9f, 9f, strokePaint)

                            // Accent left pill
                            fillPaint.color = palette.accent
                            c.drawRoundRect(RectF(cx + 6f, cyCard + 10f, cx + 9.5f, cyCard + cardH - 10f), 2f, 2f, fillPaint)

                            if (specVis.type.equals("kpi_grid", true)) {
                                val kpiVal = item.detail.ifBlank { if (item.value > 0f) "${item.value.toInt()}" else item.label.take(16) }
                                c.drawText(kpiVal.take(20), cx + 16f, cyCard + 24f, cardTitleP)
                                c.drawText(item.label.take(30), cx + 16f, cyCard + 40f, mutedPaint)
                            } else {
                                c.drawText(item.label.take(28), cx + 16f, cyCard + 20f, cardTitleP)
                                if (item.detail.isNotBlank()) {
                                    c.drawText(item.detail.take(34), cx + 16f, cyCard + 36f, mutedPaint)
                                }
                            }
                        }
                    }
                }

                return y + estHeight + 14f
            }

            fun drawMarkdownTable(rows: List<List<String>>, curY: Float, chapterHeading: String): Float {
                if (rows.isEmpty()) return curY
                val cols = rows.maxOf { it.size }.coerceIn(1, 5)
                val rowH = 24f
                val totalH = rows.size * rowH + 6f
                var y = curY
                if (y + totalH > bottomLimitY) {
                    startNewPage(withChrome = true, runningSection = chapterHeading)
                    y = topContentY
                }
                val c = canvas!!
                val colW = contentWidth.toFloat() / cols
                val headerP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 9.2f
                    typeface = sansBold
                }
                val cellP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.ink
                    textSize = 9.2f
                    typeface = sansNormal
                }
                rows.forEachIndexed { rIdx, row ->
                    val ry = y + rIdx * rowH
                    fillPaint.color = when {
                        rIdx == 0 -> palette.primary
                        rIdx % 2 == 1 -> palette.surface
                        else -> Color.WHITE
                    }
                    c.drawRect(margin, ry, pageWidth - margin, ry + rowH, fillPaint)
                    strokePaint.color = palette.border
                    strokePaint.strokeWidth = 0.7f
                    c.drawRect(margin, ry, pageWidth - margin, ry + rowH, strokePaint)
                    for (cIdx in 0 until cols) {
                        val txt = row.getOrNull(cIdx).orEmpty().replace("**", "").trim()
                        val maxChars = max(8, (colW / 5.4f).toInt())
                        c.drawText(
                            txt.take(maxChars),
                            margin + cIdx * colW + 7f,
                            ry + 15.5f,
                            if (rIdx == 0) headerP else cellP,
                        )
                    }
                }
                return y + totalH + 10f
            }

            spec.chapters.forEachIndexed { chIdx, chapter ->
                startNewPage(withChrome = true, runningSection = chapter.heading)
                var y = topContentY + 4f

                // Styled Chapter Banner
                val bannerTitleLayout = buildLayout(chapter.heading, h1Paint, contentWidth - 28, 1.15f)
                val summaryLayout = if (chapter.summary.isNotBlank()) {
                    buildLayout(chapter.summary, mutedPaint, contentWidth - 28, 1.25f)
                } else null
                val bannerH = 34f + bannerTitleLayout.height + (summaryLayout?.let { it.height + 10f } ?: 0f)

                run {
                    val c = canvas!!
                    fillPaint.color = palette.accentTint
                    c.drawRoundRect(RectF(margin, y, pageWidth - margin, y + bannerH), 12f, 12f, fillPaint)
                    fillPaint.color = palette.accent
                    c.drawRoundRect(RectF(margin, y, margin + 6f, y + bannerH), 3f, 3f, fillPaint)

                    c.drawText("CHAPTER ${String.format("%02d", chIdx + 1)}", margin + 16f, y + 18f, eyebrowPaint)
                    c.save()
                    c.translate(margin + 16f, y + 24f)
                    bannerTitleLayout.draw(c)
                    c.restore()
                    if (summaryLayout != null) {
                        c.save()
                        c.translate(margin + 16f, y + 28f + bannerTitleLayout.height)
                        summaryLayout.draw(c)
                        c.restore()
                    }
                    y += bannerH + 16f
                }

                // Render rich markdown elements line-by-line
                val lines = chapter.body.lines()
                var i = 0
                while (i < lines.size) {
                    val raw = lines[i].trim()
                    if (raw.isBlank()) {
                        y += 5f
                        i++
                        continue
                    }

                    // 1) Markdown Table
                    if (raw.startsWith("|") && raw.endsWith("|")) {
                        val tableRows = mutableListOf<List<String>>()
                        while (i < lines.size) {
                            val tLine = lines[i].trim()
                            if (!tLine.startsWith("|")) break
                            val cells = tLine.trim('|').split("|").map { it.trim() }
                            val isSeparator = cells.all { it.replace("-", "").replace(":", "").isBlank() }
                            if (!isSeparator && cells.isNotEmpty()) {
                                tableRows.add(cells)
                            }
                            i++
                        }
                        y = drawMarkdownTable(tableRows, y, chapter.heading)
                        continue
                    }

                    // 2) Subheading (### or ##)
                    val subMatch = Regex("""^#{2,4}\s+(.+)$""").matchEntire(raw)
                    if (subMatch != null) {
                        val subText = subMatch.groupValues[1].replace("**", "").trim()
                        val lay = buildLayout(subText, h2Paint, contentWidth - 12)
                        if (y + lay.height + 16f > bottomLimitY) {
                            startNewPage(withChrome = true, runningSection = chapter.heading)
                            y = topContentY
                        }
                        val c = canvas!!
                        y += 6f
                        fillPaint.color = palette.accent
                        c.drawRect(margin, y + 2f, margin + 3.5f, y + lay.height - 2f, fillPaint)
                        c.save()
                        c.translate(margin + 10f, y)
                        lay.draw(c)
                        c.restore()
                        y += lay.height + 8f
                        i++
                        continue
                    }

                    // 3) Callout / Quote (> ...)
                    if (raw.startsWith(">")) {
                        val qText = raw.removePrefix(">").trim()
                        val lay = buildLayout(makeSpannable(qText), quotePaint, contentWidth - 26)
                        val boxH = lay.height + 18f
                        if (y + boxH > bottomLimitY) {
                            startNewPage(withChrome = true, runningSection = chapter.heading)
                            y = topContentY
                        }
                        val c = canvas!!
                        fillPaint.color = palette.calloutBg
                        c.drawRoundRect(RectF(margin, y, pageWidth - margin, y + boxH), 8f, 8f, fillPaint)
                        fillPaint.color = palette.accent
                        c.drawRoundRect(RectF(margin, y, margin + 4f, y + boxH), 2f, 2f, fillPaint)
                        c.save()
                        c.translate(margin + 14f, y + 9f)
                        lay.draw(c)
                        c.restore()
                        y += boxH + 10f
                        i++
                        continue
                    }

                    // 4) Interactive Printable Checkbox (- [ ] or ☐)
                    val checkMatch = Regex("""^(?:-\s*\[([ xX]?)\]|☐)\s*(.+)$""").matchEntire(raw)
                    if (checkMatch != null) {
                        val checked = checkMatch.groupValues[1].equals("x", true)
                        val itemText = checkMatch.groupValues[2].trim()
                        val lay = buildLayout(makeSpannable(itemText), bodyPaint, contentWidth - 26)
                        if (y + lay.height + 8f > bottomLimitY) {
                            startNewPage(withChrome = true, runningSection = chapter.heading)
                            y = topContentY
                        }
                        val c = canvas!!
                        fillPaint.color = if (checked) palette.accent else Color.WHITE
                        c.drawRoundRect(RectF(margin + 2f, y + 2f, margin + 14f, y + 14f), 3f, 3f, fillPaint)
                        strokePaint.color = palette.accent
                        strokePaint.strokeWidth = 1.1f
                        c.drawRoundRect(RectF(margin + 2f, y + 2f, margin + 14f, y + 14f), 3f, 3f, strokePaint)
                        c.save()
                        c.translate(margin + 22f, y)
                        lay.draw(c)
                        c.restore()
                        y += max(18f, lay.height.toFloat()) + 6f
                        i++
                        continue
                    }

                    // 5) Worksheet fill-in lines (___ or [WORKSHEET])
                    if (raw.startsWith("___") || raw.equals("[WORKSHEET]", ignoreCase = true)) {
                        if (y + 52f > bottomLimitY) {
                            startNewPage(withChrome = true, runningSection = chapter.heading)
                            y = topContentY
                        }
                        val c = canvas!!
                        strokePaint.color = palette.border
                        strokePaint.strokeWidth = 0.9f
                        repeat(2) {
                            y += 20f
                            c.drawLine(margin, y, pageWidth - margin, y, strokePaint)
                        }
                        y += 10f
                        i++
                        continue
                    }

                    // 6) Bullet item (- or • or *)
                    val bulletMatch = Regex("""^[-•*]\s+(.+)$""").matchEntire(raw)
                    if (bulletMatch != null) {
                        val bText = bulletMatch.groupValues[1].trim()
                        val lay = buildLayout(makeSpannable(bText), bodyPaint, contentWidth - 18)
                        if (y + lay.height + 6f > bottomLimitY) {
                            startNewPage(withChrome = true, runningSection = chapter.heading)
                            y = topContentY
                        }
                        val c = canvas!!
                        fillPaint.color = palette.accent
                        c.drawCircle(margin + 6f, y + 7f, 2.8f, fillPaint)
                        c.save()
                        c.translate(margin + 16f, y)
                        lay.draw(c)
                        c.restore()
                        y += lay.height + 5f
                        i++
                        continue
                    }

                    // 7) Regular Paragraph (combine consecutive plain lines)
                    val paraSb = StringBuilder(raw)
                    i++
                    while (i < lines.size) {
                        val next = lines[i].trim()
                        if (next.isBlank() || next.startsWith("#") || next.startsWith(">") ||
                            next.startsWith("|") || next.startsWith("- ") || next.startsWith("• ") ||
                            next.startsWith("* ") || next.startsWith("☐") || next.startsWith("___")
                        ) break
                        paraSb.append(' ').append(next)
                        i++
                    }
                    val lay = buildLayout(makeSpannable(paraSb.toString()), bodyPaint, contentWidth)
                    if (y + lay.height + 8f > bottomLimitY) {
                        startNewPage(withChrome = true, runningSection = chapter.heading)
                        y = topContentY
                    }
                    val c = canvas!!
                    c.save()
                    c.translate(margin, y)
                    lay.draw(c)
                    c.restore()
                    y += lay.height + 9f
                }

                // Render chapter vector illustration / chart / tracker
                val visual = chapter.illustration ?: autoSynthesizeIllustration(chapter.heading, chapter.body)
                if (visual != null) {
                    y += 4f
                    y = drawIllustrationBlock(visual, y, chapter.heading)
                }
            }

            finishCurrentPage()
            FileOutputStream(outFile).use { fos ->
                pdf.writeTo(fos)
            }
            pdf.close()
            outFile.exists() && outFile.length() >= 120
        }.getOrDefault(false)
    }
}
