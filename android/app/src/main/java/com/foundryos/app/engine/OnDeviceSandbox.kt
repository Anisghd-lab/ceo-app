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

    fun sandboxesBaseDir(): File = rootDir

    fun listOutputFiles(companyId: String): List<File> {
        val outDir = File(companyRoot(companyId), "output")
        return outDir.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedByDescending { it.lastModified() } ?: emptyList()
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
            lower.endsWith(".svg") -> {
                val text = String(bytes, Charsets.UTF_8)
                val isSvg = text.contains("<svg", ignoreCase = true) && bytes.size >= 120
                if (isSvg) QaAuditResult(true, 93, "Valid SVG vector graphic verified (${bytes.size} bytes)")
                else QaAuditResult(false, 45, "Invalid or empty SVG graphic")
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
