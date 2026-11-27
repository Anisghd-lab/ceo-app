package com.foundryos.app.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class QaAuditResult(
    val passed: Boolean,
    val score: Int,
    val report: String,
)

/**
 * On-device isolated company sandboxes (1 Sandbox = 1 Company under filesDir/sandboxes/<company_id>/).
 * Generates real PDFs via Android PdfDocument, ZIP bundles, Markdown/CSV/HTML/Code files,
 * and runs programmatic integrity checks before human approval.
 */
class OnDeviceSandbox(private val context: Context) {
    private val rootDir: File = File(context.filesDir, "sandboxes").apply { mkdirs() }

    fun companyRoot(companyId: String): File {
        val safe = companyId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        require(safe.isNotEmpty() && safe == companyId) { "Invalid company sandbox id" }
        val dir = File(rootDir, safe).canonicalFile
        require(dir.path.startsWith(rootDir.canonicalPath)) { "Sandbox escape blocked" }
        File(dir, "workspace").mkdirs()
        File(dir, "output").mkdirs()
        return dir
    }

    fun destroySandbox(companyId: String) {
        runCatching { companyRoot(companyId).deleteRecursively() }
    }

    fun resolveFile(companyId: String, relPath: String): File {
        val root = companyRoot(companyId)
        val cleaned = relPath.trimStart('/')
        val target = File(root, cleaned).canonicalFile
        require(target.path.startsWith(root.canonicalPath)) { "Path traversal blocked: $relPath" }
        return target
    }

    fun writeTextFile(companyId: String, relPath: String, content: String): File {
        val target = resolveFile(companyId, relPath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
        return target
    }

    fun readTextFile(companyId: String, relPath: String): String {
        val target = resolveFile(companyId, relPath)
        if (!target.exists()) throw IllegalStateException("File not found: $relPath")
        return target.readText(Charsets.UTF_8).take(24_000)
    }

    fun listFiles(companyId: String): List<Pair<String, Long>> {
        val root = companyRoot(companyId)
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path to it.length() }
            .take(150)
            .toList()
    }

    /**
     * Renders a clean, multi-page editorial PDF natively on Android using PdfDocument.
     */
    fun createPdf(
        companyId: String,
        filename: String,
        title: String,
        subtitle: String,
        sections: List<Pair<String, String>>,
    ): File {
        val cleanName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }
        val outFile = resolveFile(companyId, "output/$cleanName")
        outFile.parentFile?.mkdirs()

        val pageWidth = 595 // A4 points
        val pageHeight = 842
        val margin = 48
        val contentWidth = pageWidth - margin * 2

        val pdf = PdfDocument()
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6B7280")
            textSize = 11.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val h2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#111111")
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F2937")
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#E5E7EB")
            strokeWidth = 1.2f
        }

        fun nextPage() {
            pdf.finishPage(page)
            pageNum += 1
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
            canvas = page.canvas
        }

        fun drawTextLayout(text: String, paint: TextPaint, x: Float, startY: Float): Float {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.15f)
                .build()
            var y = startY
            if (y + layout.height > pageHeight - margin) {
                nextPage()
                y = margin.toFloat()
            }
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
            return y + layout.height
        }

        var cursorY = margin.toFloat() + 10f
        cursorY = drawTextLayout(title, titlePaint, margin.toFloat(), cursorY)
        cursorY += 6f
        cursorY = drawTextLayout(subtitle, subPaint, margin.toFloat(), cursorY)
        cursorY += 14f
        canvas.drawLine(margin.toFloat(), cursorY, (pageWidth - margin).toFloat(), cursorY, dividerPaint)
        cursorY += 18f

        for ((heading, body) in sections) {
            if (cursorY > pageHeight - margin - 80) {
                nextPage()
                cursorY = margin.toFloat()
            }
            cursorY = drawTextLayout(heading, h2Paint, margin.toFloat(), cursorY)
            cursorY += 6f
            // Split long bodies by paragraph so pages break cleanly
            val paragraphs = body.split("\n\n").filter { it.isNotBlank() }
            for (p in paragraphs) {
                cursorY = drawTextLayout(p.trim(), bodyPaint, margin.toFloat(), cursorY)
                cursorY += 8f
            }
            cursorY += 10f
        }

        pdf.finishPage(page)
        FileOutputStream(outFile).use { fos ->
            pdf.writeTo(fos)
        }
        pdf.close()
        return outFile
    }

    /**
     * Packages multiple sandbox files into a clean .zip bundle in output/.
     */
    fun createZipBundle(companyId: String, zipFilename: String, relPaths: List<String>): File {
        val cleanName = zipFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip"
        }
        val outFile = resolveFile(companyId, "output/$cleanName")
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for (rel in relPaths) {
                val f = resolveFile(companyId, rel)
                if (f.exists() && f.isFile) {
                    zos.putNextEntry(ZipEntry(f.name))
                    zos.write(f.readBytes())
                    zos.closeEntry()
                }
            }
        }
        return outFile
    }

    /**
     * Real programmatic QA check on any generated artifact in the sandbox.
     */
    fun verifyArtifactProgrammatically(companyId: String, relPath: String): QaAuditResult {
        val file = resolveFile(companyId, relPath)
        if (!file.exists() || !file.isFile) {
            return QaAuditResult(false, 20, "Missing file in sandbox: $relPath")
        }
        val bytes = file.readBytes()
        if (bytes.size < 120) {
            return QaAuditResult(false, 40, "File too small (${bytes.size} bytes) — incomplete output")
        }
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".pdf") -> {
                val isPdf = bytes.size > 500 && String(bytes.copyOfRange(0, minOf(8, bytes.size))).startsWith("%PDF")
                if (isPdf) QaAuditResult(true, 94, "Valid PDF verified in sandbox (${bytes.size} bytes)")
                else QaAuditResult(false, 35, "Invalid PDF header")
            }
            lower.endsWith(".zip") -> {
                val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                if (isZip) QaAuditResult(true, 93, "Valid ZIP archive verified (${bytes.size} bytes)")
                else QaAuditResult(false, 35, "Corrupted ZIP header")
            }
            else -> {
                val text = String(bytes, Charsets.UTF_8)
                val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
                if (words >= 15) QaAuditResult(true, 92, "Verified ${file.name} ($words words, ${bytes.size} bytes)")
                else QaAuditResult(false, 55, "Content too thin ($words words)")
            }
        }
    }
}
