package com.foundryos.app.engine

import android.content.Context
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

    fun writeUploadBytes(companyId: String, filename: String, bytes: ByteArray): File {
        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "upload.bin" }
        val target = resolveFile(companyId, "workspace/uploads/$safeName")
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
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
     * Renders a rich, illustrated, multi-page editorial PDF book/workbook natively on Android.
     */
    fun createIllustratedBookPdf(
        companyId: String,
        filename: String,
        spec: PdfBookSpec,
    ): File {
        val cleanName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
        }
        val outFile = resolveFile(companyId, "output/$cleanName")
        outFile.parentFile?.mkdirs()

        val renderedNative = PdfBookRenderer.renderToFile(outFile, spec)
        if (!renderedNative || !outFile.exists() || outFile.length() < 120) {
            return writeBasicPdfFallback(
                outFile = outFile,
                title = spec.title,
                subtitle = spec.subtitle,
                sections = spec.chapters.map { it.heading to it.body },
            )
        }
        return outFile
    }

    /**
     * Renders a clean, multi-page editorial PDF natively on Android using PdfBookRenderer.
     */
    fun createPdf(
        companyId: String,
        filename: String,
        title: String,
        subtitle: String,
        sections: List<Pair<String, String>>,
        author: String = "",
        theme: String = "",
        highlights: List<String> = emptyList(),
    ): File {
        val bookSpec = PdfBookSpec(
            title = title,
            subtitle = subtitle,
            author = author.ifBlank { subtitle.substringBefore("·").trim() },
            theme = theme.ifBlank { PdfBookRenderer.inferTheme(title, subtitle) },
            highlights = highlights.ifEmpty { sections.take(4).map { it.first } },
            chapters = sections.map { (heading, body) ->
                PdfBookChapter(
                    heading = heading,
                    body = body,
                    illustration = PdfBookRenderer.autoSynthesizeIllustration(heading, body),
                )
            },
        )
        return createIllustratedBookPdf(companyId, filename, bookSpec)
    }

    /**
     * Minimal valid %PDF-1.4 fallback used only if Android PdfDocument cannot render (e.g. JVM unit tests).
     */
    private fun writeBasicPdfFallback(
        outFile: File,
        title: String,
        subtitle: String,
        sections: List<Pair<String, String>>,
    ): File {
        fun escPdf(s: String): String = s
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(Regex("[\\r\\n]+"), " ")
            .take(110)
        val lines = mutableListOf<String>()
        lines += escPdf(title)
        if (subtitle.isNotBlank()) lines += escPdf(subtitle)
        for ((h, b) in sections) {
            lines += escPdf(h)
            b.chunked(95).take(24).forEach { lines += escPdf(it) }
        }
        val stream = buildString {
            append("BT\n/F1 12 Tf\n48 790 Td\n16 TL\n")
            lines.forEachIndexed { idx, line ->
                if (idx == 0) append("($line) Tj\n") else append("T* ($line) Tj\n")
            }
            append("ET\n")
        }
        val streamBytes = stream.toByteArray(Charsets.US_ASCII)
        val sb = StringBuilder()
        val offsets = mutableListOf<Int>()
        fun addObj(num: Int, body: String) {
            offsets += sb.length
            sb.append("$num 0 obj\n$body\nendobj\n")
        }
        sb.append("%PDF-1.4\n")
        addObj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        addObj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        addObj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        addObj(4, "<< /Length ${streamBytes.size} >>\nstream\n$stream\nendstream")
        addObj(5, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        val xrefPos = sb.length
        sb.append("xref\n0 6\n0000000000 65535 f \n")
        for (off in offsets) {
            sb.append(String.format("%010d 00000 n \n", off))
        }
        sb.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        outFile.writeText(sb.toString(), Charsets.US_ASCII)
        return outFile
    }

    /**
     * Packages multiple sandbox files into a clean .zip bundle in output/.
     */
    fun createZipBundle(
        companyId: String,
        zipFilename: String,
        relPaths: List<String>,
        readmeText: String = "",
    ): File {
        val cleanName = zipFilename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip"
        }
        val outFile = resolveFile(companyId, "output/$cleanName")
        outFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            if (readmeText.isNotBlank()) {
                zos.putNextEntry(ZipEntry("00_START_HERE.txt"))
                zos.write(readmeText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            for (rel in relPaths) {
                val f = resolveFile(companyId, rel)
                if (f.exists() && f.isFile && f.canonicalPath != outFile.canonicalPath) {
                    zos.putNextEntry(ZipEntry(f.name))
                    zos.write(f.readBytes())
                    zos.closeEntry()
                }
            }
        }
        return outFile
    }

    fun createXlsx(companyId: String, filename: String, sheets: List<DigitalProductFactory.SheetSpec>): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".xlsx", ignoreCase = true)) it else "$it.xlsx"
        }
        return DigitalProductFactory.createXlsxWorkbook(resolveFile(companyId, "output/$clean"), sheets)
    }

    fun createPptx(
        companyId: String,
        filename: String,
        title: String,
        subtitle: String,
        author: String,
        theme: String,
        slides: List<DigitalProductFactory.SlideSpec>,
    ): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".pptx", ignoreCase = true)) it else "$it.pptx"
        }
        return DigitalProductFactory.createPptxDeck(resolveFile(companyId, "output/$clean"), title, subtitle, author, theme, slides)
    }

    fun createDocx(companyId: String, filename: String, title: String, subtitle: String, sections: List<Pair<String, String>>): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".docx", ignoreCase = true)) it else "$it.docx"
        }
        return DigitalProductFactory.createDocxDocument(resolveFile(companyId, "output/$clean"), title, subtitle, sections)
    }

    fun createPngGraphic(
        companyId: String,
        filename: String,
        title: String,
        subtitle: String,
        badge: String = "",
        bullets: List<String> = emptyList(),
        priceTag: String = "",
        theme: String = "executive",
        widthPx: Int = 1200,
        heightPx: Int = 1500,
    ): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".png", ignoreCase = true)) it else "$it.png"
        }
        return DigitalProductFactory.createPngGraphic(
            resolveFile(companyId, "output/$clean"),
            title, subtitle, badge, bullets, priceTag, theme, widthPx, heightPx,
        )
    }

    fun createCsv(companyId: String, filename: String, headers: List<String>, rows: List<List<String>>): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".csv", ignoreCase = true)) it else "$it.csv"
        }
        return DigitalProductFactory.createCsvDataset(resolveFile(companyId, "output/$clean"), headers, rows)
    }

    fun createNotionPack(
        companyId: String,
        filename: String,
        packTitle: String,
        setupGuide: String,
        pages: List<Pair<String, String>>,
        databases: List<DigitalProductFactory.SheetSpec>,
    ): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".zip", ignoreCase = true)) it else "${it}_notion_pack.zip"
        }
        return DigitalProductFactory.createNotionTemplatePack(
            resolveFile(companyId, "output/$clean"),
            packTitle, setupGuide, pages, databases,
        )
    }

    fun createWebApp(companyId: String, filename: String, title: String, subtitle: String, html: String, theme: String): File {
        val clean = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").let {
            if (it.endsWith(".html", ignoreCase = true)) it else "$it.html"
        }
        return DigitalProductFactory.createInteractiveWebApp(resolveFile(companyId, "output/$clean"), title, subtitle, html, theme)
    }

    fun sandboxesBaseDir(): File = rootDir

    fun listOutputFiles(companyId: String): List<File> {
        val outDir = File(companyRoot(companyId), "output")
        return outDir.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Real programmatic QA check on any generated sellable digital artifact in the sandbox.
     */
    fun verifyArtifactProgrammatically(companyId: String, relPath: String): QaAuditResult {
        val file = resolveFile(companyId, relPath)
        if (!file.exists() || !file.isFile) {
            return QaAuditResult(false, 20, "Missing file in sandbox: $relPath")
        }
        val bytes = file.readBytes()
        if (bytes.size < 64) {
            return QaAuditResult(false, 40, "File too small (${bytes.size} bytes) — incomplete output")
        }
        val lower = file.name.lowercase()
        val isZipHeader = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        fun zipEntryNames(): Set<String> = runCatching {
            val names = mutableSetOf<String>()
            java.util.zip.ZipFile(file).use { zf ->
                val en = zf.entries()
                while (en.hasMoreElements()) names.add(en.nextElement().name)
            }
            names
        }.getOrDefault(emptySet())

        return when {
            lower.endsWith(".pdf") -> {
                val isPdf = bytes.size > 500 && String(bytes.copyOfRange(0, minOf(8, bytes.size))).startsWith("%PDF")
                if (isPdf) QaAuditResult(true, 94, "Valid PDF verified in sandbox (${bytes.size} bytes)")
                else QaAuditResult(false, 35, "Invalid PDF header")
            }
            lower.endsWith(".xlsx") -> {
                val entries = if (isZipHeader) zipEntryNames() else emptySet()
                if ("xl/workbook.xml" in entries && entries.any { it.startsWith("xl/worksheets/sheet") }) {
                    val sheetCount = entries.count { it.startsWith("xl/worksheets/sheet") }
                    QaAuditResult(true, 95, "Valid Excel/Sheets .xlsx workbook verified ($sheetCount sheets, ${bytes.size} bytes)")
                } else QaAuditResult(false, 35, "Corrupted or incomplete .xlsx workbook")
            }
            lower.endsWith(".pptx") -> {
                val entries = if (isZipHeader) zipEntryNames() else emptySet()
                if ("ppt/presentation.xml" in entries && entries.any { it.startsWith("ppt/slides/slide") }) {
                    val slideCount = entries.count { it.startsWith("ppt/slides/slide") }
                    QaAuditResult(true, 95, "Valid PowerPoint/Slides .pptx deck verified ($slideCount slides, ${bytes.size} bytes)")
                } else QaAuditResult(false, 35, "Corrupted or incomplete .pptx presentation")
            }
            lower.endsWith(".docx") -> {
                val entries = if (isZipHeader) zipEntryNames() else emptySet()
                if ("word/document.xml" in entries) {
                    QaAuditResult(true, 94, "Valid Word/Docs .docx template verified (${bytes.size} bytes)")
                } else QaAuditResult(false, 35, "Corrupted or incomplete .docx document")
            }
            lower.endsWith(".png") -> {
                val isPng = bytes.size >= 8 &&
                    bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
                if (isPng) QaAuditResult(true, 94, "Valid high-res PNG graphic verified (${bytes.size} bytes)")
                else QaAuditResult(false, 35, "Invalid PNG header")
            }
            lower.endsWith(".zip") -> {
                val entries = if (isZipHeader) zipEntryNames() else emptySet()
                if (isZipHeader && entries.isNotEmpty()) {
                    QaAuditResult(true, 94, "Valid ZIP bundle verified (${entries.size} files, ${bytes.size} bytes)")
                } else QaAuditResult(false, 35, "Empty or corrupted ZIP archive")
            }
            lower.endsWith(".csv") -> {
                val lineCount = String(bytes, Charsets.UTF_8).lines().count { it.isNotBlank() }
                if (lineCount >= 2) QaAuditResult(true, 93, "Valid CSV dataset verified ($lineCount rows, ${bytes.size} bytes)")
                else QaAuditResult(false, 50, "CSV dataset has fewer than 2 rows")
            }
            lower.endsWith(".svg") -> {
                val text = String(bytes, Charsets.UTF_8)
                val isSvg = text.contains("<svg", ignoreCase = true) && bytes.size >= 120
                if (isSvg) QaAuditResult(true, 93, "Valid SVG vector graphic verified (${bytes.size} bytes)")
                else QaAuditResult(false, 45, "Invalid or empty SVG graphic")
            }
            lower.endsWith(".html") -> {
                val text = String(bytes, Charsets.UTF_8)
                val okHtml = text.contains("<html", ignoreCase = true) || text.contains("<body", ignoreCase = true) || text.contains("<div", ignoreCase = true)
                if (okHtml && bytes.size >= 200) QaAuditResult(true, 94, "Valid interactive HTML5 app/page verified (${bytes.size} bytes)")
                else QaAuditResult(false, 45, "Incomplete HTML deliverable")
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
