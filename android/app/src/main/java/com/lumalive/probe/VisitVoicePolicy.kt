package com.lumalive.probe

import java.text.Normalizer
import java.util.Locale

enum class VisitCommand { CAPTURE, REPEAT, QUIET, PAUSE, FINISH, HELP, QUESTION, CLARIFY }

fun spokenKey(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

fun visitCommand(text: String): VisitCommand {
    return interpretVisitIntent(text, hasArtwork = true).command
}

/** Per-visit admission; commands don't consume model turns. All accepted queries count, including failures. */
class VisitVoicePolicy(private val startedAt: Long, private val maxTurns: Int = 8, private val maxDurationMs: Long = 300_000) {
    var turns = 0; private set
    private var previous = ""
    private var previousAt = Long.MIN_VALUE
    fun expired(now: Long) = now - startedAt >= maxDurationMs
    fun admit(text: String, now: Long, confidence: Float, spokenByLuma: String = "", allowUncertain: Boolean = false): Boolean {
        val key = spokenKey(text)
        if (key.isBlank() || text.length > 1000 || expired(now) || (!allowUncertain && confidence >= 0 && confidence < 0.45f)) return false
        val own = spokenKey(spokenByLuma)
        val control = visitCommand(text)
        val interrupt = control in setOf(VisitCommand.QUIET, VisitCommand.PAUSE, VisitCommand.FINISH) && key.split(' ').size <= 7
        if (!interrupt && key.length >= 12 && own.isNotBlank() && (own.contains(key) || key.contains(own))) return false
        if (previous == key && now - previousAt < 2500) return false
        previous = key; previousAt = now
        return true
    }
    fun modelTurn(now: Long): Boolean {
        if (expired(now) || turns >= maxTurns) return false
        turns++; return true
    }
}
