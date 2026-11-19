package com.vision.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The memory keeper. It runs by itself in the background, so Vision does not have to remember to save.
 * After a voice session ends, or after every 6 of his text messages, it reads the new part of the
 * shared history and asks Gemini (no tools, low thinking) for two things:
 *   1. lasting facts, appended to profile.md, facts/, routines/ or projects/
 *   2. a short summary of what was discussed or done, appended to history/<date>.md
 * so Vision can also recall what happened on earlier days.
 * Nothing is rewritten or deleted, repeated lines are skipped, and anything that looks like a code,
 * PIN, bank or money detail is dropped before saving (SafetyRules).
 */
object MemoryKeeper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    @Volatile private var doneUntil = 0L

    private const val SYSTEM = """You keep the long-term memory of a personal phone assistant called Vision.
From the conversation, extract two things.
1. Durable facts about the user: who he is, preferences, people in his life, projects and the decisions he made, routines, and standing instructions. Skip small talk, one-off questions, tool results, and anything Vision said that he did not confirm.
2. A short summary (1 or 2 sentences) of what was discussed or done in this conversation, including any decision or unfinished task.
Never include passwords, PINs, OTPs, card or account numbers, or any bank or money detail.
Do not repeat anything listed under ALREADY KNOWN.
Write in the language he used.
Answer with one JSON object: {"facts":[{"path":"facts/topic.md","line":"one short sentence"}],"summary":"..."}.
facts has at most 8 items and may be empty. summary may be "" if there was only small talk.
Allowed paths: profile.md (who he is), facts/<topic>.md, routines/<topic>.md, projects/<topic>.md. Use short lowercase topic names with hyphens. Do not write skills; those are saved separately."""

    fun maybeRun(force: Boolean = false) {
        if (!Prefs.memoryOn || Prefs.apiKey.isBlank()) return
        val fresh = MemoryStore.turns(60).filter { it.ts > doneUntil && !it.text.startsWith("[Agent]") }
        val userCount = fresh.count { it.user }
        if (userCount == 0) return
        if (!force && userCount < 6) return
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                val convo = fresh.joinToString("\n") { (if (it.user) "User: " else "Vision: ") + it.text.take(400) }
                val prompt = "ALREADY KNOWN:\n" + MemoryStore.knownSummary() + "\n\nCONVERSATION:\n" + convo
                val raw = ChatEngine.extract(SYSTEM, prompt).getOrNull()
                if (raw != null) {
                    apply(raw)
                    doneUntil = fresh.last().ts
                }
            } catch (e: Exception) {
                // Best effort: the next run tries again with the same turns.
            } finally {
                busy.set(false)
            }
        }
    }

    private fun apply(raw: String) {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { JSONObject(clean) }.getOrNull()
        val facts = obj?.optJSONArray("facts") ?: runCatching { JSONArray(clean) }.getOrNull() ?: JSONArray()
        val summary = obj?.optString("summary").orEmpty().trim().take(400)

        var saved = 0
        for (i in 0 until facts.length()) {
            if (saved >= 8) break
            val o = facts.optJSONObject(i) ?: continue
            val path = o.optString("path").trim()
            val line = o.optString("line").trim().take(300)
            if (line.length < 6) continue
            if (SafetyRules.refusal(line) != null) continue
            val okPath = path == "profile.md" || path.startsWith("facts/") ||
                path.startsWith("routines/") || path.startsWith("projects/")
            if (!okPath) continue
            if (MemoryStore.write(path, "- $line", append = true).startsWith("Saved")) saved++
        }

        if (summary.length >= 10 && SafetyRules.refusal(summary) == null) {
            val now = java.util.Date()
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now)
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(now)
            MemoryStore.write("history/$day.md", "- $hm $summary", append = true)
        }
    }
}
