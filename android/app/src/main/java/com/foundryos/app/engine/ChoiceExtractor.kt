package com.foundryos.app.engine

import com.foundryos.app.data.ChoiceOption
import com.foundryos.app.data.ChoicePromptData
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts structured or markdown-formatted interactive choice prompts from CEO / bot replies
 * so the Founder can answer with a single tap (ChatGPT-style).
 */
object ChoiceExtractor {

    private val choiceCueRegex = Regex(
        """(\?|choisi[rs]?|choix|option[s]?|préfère[sz]?|souhaite[sz]?|veux[- ]tu|voulez[- ]vous|quelle\s|quel\s|lequel|laquelle|direction|priorité|choose|pick|select|which\s|would you like|prefer|what would you|clique[rz]?)""",
        RegexOption.IGNORE_CASE,
    )

    private val numberedOrLetteredRegex = Regex(
        """^\s*(?:(?:option|choix)\s+)?([1-9]|[A-Fa-f])[.):\-]\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    private val bulletExplicitOptionRegex = Regex(
        """^\s*[-•*]\s+\*{0,2}(?:option|choix)\s*[A-F1-9]?[:.\-)]?\s*\*{0,2}\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parseStructuredChoices(obj: JSONObject): Pair<String, List<ChoiceOption>>? {
        val arr = obj.optJSONArray("choices") ?: obj.optJSONArray("options") ?: return null
        val parsed = mutableListOf<ChoiceOption>()
        for (i in 0 until minOf(arr.length(), 6)) {
            when (val entry = arr.opt(i)) {
                is String -> {
                    val clean = stripMarkdown(entry).trim()
                    if (clean.isNotBlank()) {
                        parsed.add(
                            ChoiceOption(
                                id = "opt_${i + 1}",
                                label = clean.take(100),
                                description = "",
                                value = clean,
                            )
                        )
                    }
                }
                is JSONObject -> {
                    val rawLabel = entry.optString("label")
                        .ifBlank { entry.optString("title") }
                        .ifBlank { entry.optString("text") }
                        .ifBlank { entry.optString("name") }
                    val cleanLabel = stripMarkdown(rawLabel).trim()
                    if (cleanLabel.isBlank()) continue
                    val desc = stripMarkdown(
                        entry.optString("description")
                            .ifBlank { entry.optString("detail") }
                            .ifBlank { entry.optString("subtitle") }
                    ).trim()
                    val value = entry.optString("value").trim().ifBlank { cleanLabel }
                    val id = entry.optString("id").trim().ifBlank { "opt_${i + 1}" }
                    parsed.add(
                        ChoiceOption(
                            id = id,
                            label = cleanLabel.take(100),
                            description = desc.take(140),
                            value = value,
                        )
                    )
                }
            }
        }
        if (parsed.size < 2) return null
        val reply = obj.optString("reply")
            .ifBlank { obj.optString("question") }
            .ifBlank { obj.optString("message") }
            .ifBlank { obj.optString("summary") }
            .trim()
            .ifBlank { "Quelle option choisis-tu ?" }
        return reply to parsed
    }

    /**
     * Conservative fallback: detects interactive options from plain markdown replies
     * ONLY when the message clearly asks a question / offers a choice and lists 2..6 concise options.
     */
    fun detectChoicesFromMarkdown(rawText: String): Pair<String, List<ChoiceOption>>? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) return null

        val lines = trimmed.lines()
        val optionLines = mutableListOf<Pair<Int, String>>()
        for (idx in lines.indices) {
            val line = lines[idx].trim()
            if (line.isBlank()) continue
            val m1 = numberedOrLetteredRegex.matchEntire(line)
            if (m1 != null) {
                optionLines.add(idx to m1.groupValues[2].trim())
                continue
            }
            val m2 = bulletExplicitOptionRegex.matchEntire(line)
            if (m2 != null) {
                optionLines.add(idx to m2.groupValues[1].trim())
            }
        }

        if (optionLines.size !in 2..6) return null

        // Check non-option context for a genuine choice/question cue so factual numbered lists stay normal text
        val optionIndices = optionLines.map { it.first }.toSet()
        val contextLines = lines.filterIndexed { idx, line -> idx !in optionIndices && line.isNotBlank() }
        val contextText = contextLines.joinToString("\n").trim()
        if (!choiceCueRegex.containsMatchIn(contextText)) return null

        // Build clean ChoiceOption objects
        val options = mutableListOf<ChoiceOption>()
        for ((pos, pair) in optionLines.withIndex()) {
            val rawItem = pair.second
            val (label, desc) = splitLabelAndDescription(rawItem)
            if (label.isBlank() || label.length > 110) return null
            options.add(
                ChoiceOption(
                    id = "opt_${pos + 1}",
                    label = label,
                    description = desc.take(140),
                    value = if (desc.isNotBlank()) "$label — $desc".take(160) else label,
                )
            )
        }
        if (options.size < 2) return null

        val cleanIntro = contextText.ifBlank { "Quelle option choisis-tu ?" }
        return cleanIntro to options
    }

    fun toPayloadJson(data: ChoicePromptData): String {
        val arr = JSONArray()
        for (opt in data.options) {
            arr.put(
                JSONObject()
                    .put("id", opt.id)
                    .put("label", opt.label)
                    .put("description", opt.description)
                    .put("value", opt.value)
            )
        }
        return JSONObject()
            .put("options", arr)
            .put("selected_choice_id", data.selectedOptionId ?: "")
            .toString()
    }

    fun fromPayloadJson(payload: JSONObject): ChoicePromptData? {
        val arr = payload.optJSONArray("options") ?: return null
        val opts = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("label").trim()
            if (label.isBlank()) return@mapNotNull null
            ChoiceOption(
                id = o.optString("id").ifBlank { "opt_${i + 1}" },
                label = label,
                description = o.optString("description").trim(),
                value = o.optString("value").trim().ifBlank { label },
            )
        }
        if (opts.isEmpty()) return null
        val selected = payload.optString("selected_choice_id").trim().ifBlank { null }
        return ChoicePromptData(options = opts, selectedOptionId = selected)
    }

    private fun splitLabelAndDescription(raw: String): Pair<String, String> {
        // Case 1: **Bold Title** — description / : description / - description
        val boldLead = Regex("""^\*\*(.+?)\*\*\s*(?:[—–:\-]\s*(.+))?$""").matchEntire(raw.trim())
        if (boldLead != null) {
            val lbl = stripMarkdown(boldLead.groupValues[1]).trim()
            val dsc = stripMarkdown(boldLead.groupValues[2]).trim()
            return lbl to dsc
        }
        // Case 2: Title — description or Title : description
        for (sep in listOf(" — ", " – ", " : ", ": ", " - ")) {
            val idx = raw.indexOf(sep)
            if (idx in 2..70) {
                val lbl = stripMarkdown(raw.substring(0, idx)).trim()
                val dsc = stripMarkdown(raw.substring(idx + sep.length)).trim()
                if (lbl.isNotBlank()) return lbl to dsc
            }
        }
        return stripMarkdown(raw).trim() to ""
    }

    private fun stripMarkdown(text: String): String =
        text.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""__(.+?)__"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""^\s*#+\s*"""), "")
            .trim()
}
