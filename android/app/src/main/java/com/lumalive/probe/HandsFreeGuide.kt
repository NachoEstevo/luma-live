package com.lumalive.probe

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns conversation decisions; Android/SDK resources live behind VisitExperience. */
class HandsFreeGuide(
    private val experience: VisitExperience,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    idleSeconds: Int = 25,
    private val log: (String) -> Unit = {},
) {
    private val mutable = MutableStateFlow(VisitState(idleSeconds = idleSeconds))
    val state = mutable.asStateFlow()
    private var policy: VisitVoicePolicy? = null
    private var operation: Job? = null
    private var idleJob: Job? = null
    private var lifetimeJob: Job? = null
    private var ended: (() -> Unit)? = null
    private var session = 0L
    private var serial = 0L
    private var idleSerial = 0L
    private var startedAt = 0L
    private var listenStartedAt = 0L
    private var micOpen = false
    private var resumeAfterPause = false
    private var confirmingCapture = false
    private var repairs = 0
    private var ownSpeech = ""
    private var speechEndedAt = Long.MIN_VALUE
    private data class Run(val session: Long, val serial: Long)

    val remainingMs: Long get() = if (state.value.active) (MAX_VISIT_MS - (now() - startedAt)).coerceAtLeast(0) else 0

    fun configureIdleWindow(seconds: Int) {
        require(seconds in setOf(25, 45, 60))
        mutable.update { it.copy(idleSeconds = seconds) }
        if (state.value.phase == VisitPhase.LISTENING) armIdle(true)
    }

    /** Repeated activation resumes pause, preserving artwork, history and request quota. */
    fun start(onEnded: () -> Unit) {
        // elapsedRealtime includes deep sleep; coroutine delays need not have run while asleep.
        if (expired()) stopInternal("La visita venció; obra y conversación liberadas", notifyEnded = false)
        when (state.value.phase) {
            VisitPhase.PAUSED -> { ended = onEnded; log("activation:resume"); resume(); return }
            VisitPhase.PAUSING -> { ended = onEnded; log("activation:resume_pending"); resumeAfterPause = true; return }
            VisitPhase.IDLE -> Unit
            else -> return
        }
        check(experience.readyForVisit) { "Primero detené la prueba manual y conectá los lentes" }
        session++; startedAt = now(); ended = onEnded
        log("activation:new_visit")
        policy = VisitVoicePolicy(startedAt, maxDurationMs = MAX_VISIT_MS)
        confirmingCapture = false; repairs = 0
        experience.ownVisit(true)
        mutable.value = VisitState(VisitPhase.CONNECTING, status = "Preparando Luma…", hasArtwork = experience.hasArtwork, idleSeconds = state.value.idleSeconds)
        val lifetimeSession = session
        lifetimeJob = scope.launch {
            while (session == lifetimeSession && state.value.active) {
                val remaining = MAX_VISIT_MS - (now() - startedAt)
                if (remaining <= 0) break
                delay(remaining)
            }
            if (session != lifetimeSession || !state.value.active) return@launch
            if (state.value.phase == VisitPhase.PAUSED) stop("La visita venció; obra y conversación liberadas")
            else finish("Cierro esta visita. Podemos empezar otra cuando quieras.")
        }
        runOperation { run -> say("Estoy acá.", run); openMic(run); listenTurn(run) }
    }

    private fun resume() {
        if (remainingMs <= 0) { stop("La visita venció. Volvé a llamarme para empezar otra."); return }
        confirmingCapture = false; repairs = 0; resumeAfterPause = false
        ownSpeech = ""; speechEndedAt = Long.MIN_VALUE
        runOperation { run ->
            say(if (experience.artworkFresh) "Seguimos con esta obra." else "Estoy acá.", run)
            openMic(run); listenTurn(run)
        }
    }

    private fun expired() = state.value.active && now() - startedAt >= MAX_VISIT_MS

    /** Purge overdue context before foreground UI or wearer input can use it after deep sleep. */
    fun expireOverdueContext(): Boolean {
        if (!expired()) return false
        stop("La visita venció; obra y conversación liberadas")
        return true
    }

    private fun runOperation(action: suspend (Run) -> Unit) {
        cancelIdle()
        val previous = operation
        val run = Run(session, ++serial)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { previous?.cancelAndJoin(); ensureCurrent(run); action(run) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current(run)) fail(e.message ?: "No pude continuar la conversación") }
        }
        operation = next
        next.invokeOnCompletion { if (operation === next) operation = null }
        next.start()
    }

    private fun current(run: Run) = run.session == session && run.serial == serial && state.value.active
    private suspend fun ensureCurrent(run: Run) {
        currentCoroutineContext().ensureActive()
        if (expireOverdueContext()) throw CancellationException("La visita venció")
        if (!current(run)) throw CancellationException("La conversación cambió de estado")
    }
    private fun publish(phase: VisitPhase, status: String, run: Run) {
        if (current(run)) mutable.update { it.copy(phase = phase, listening = micOpen, status = status, hasArtwork = experience.hasArtwork, turns = policy?.turns ?: it.turns) }
    }
    private suspend fun say(text: String, run: Run) {
        ensureCurrent(run); ownSpeech = text
        publish(VisitPhase.SPEAKING, if (micOpen) "Luma está hablando · podés interrumpir" else "Luma está hablando", run)
        try { experience.say(text) } finally { if (current(run)) speechEndedAt = now() }
        ensureCurrent(run)
    }
    private suspend fun openMic(run: Run) {
        ensureCurrent(run)
        if (micOpen) return
        publish(VisitPhase.CONNECTING, "Abriendo la escucha de los lentes…", run)
        experience.openListening(::onText, ::fail)
        ensureCurrent(run); micOpen = true
    }
    private suspend fun listenTurn(run: Run) {
        ensureCurrent(run); check(micOpen) { "Los lentes no confirmaron la escucha" }
        publish(VisitPhase.LISTENING, if (experience.hasArtwork) "Te escucho sobre esta obra" else "Te escucho · pedime que mire una obra", run)
        armIdle(true)
        experience.cue(VisitCue.READY)
        ensureCurrent(run)
    }
    private fun cancelIdle() { idleSerial++; idleJob?.cancel(); idleJob = null }
    private fun armIdle(resetStart: Boolean) {
        cancelIdle()
        if (state.value.phase != VisitPhase.LISTENING) return
        if (resetStart) listenStartedAt = now()
        val marker = idleSerial
        val deadline = minOf(now() + state.value.idleSeconds * 1000L, listenStartedAt + MAX_UNBROKEN_LISTEN_MS)
        idleJob = scope.launch {
            delay((deadline - now()).coerceAtLeast(1))
            if (marker == idleSerial && state.value.phase == VisitPhase.LISTENING) pause(true)
        }
    }
    private fun echoFor(text: String): String {
        val recent = state.value.phase == VisitPhase.SPEAKING || (speechEndedAt >= 0 && now() - speechEndedAt < 2000)
        return if (recent && spokenKey(text).length >= 12) ownSpeech else ""
    }

    private fun onText(text: String, final: Boolean, confidence: Float) {
        if (expireOverdueContext()) return
        if (!micOpen || state.value.phase in setOf(VisitPhase.IDLE, VisitPhase.PAUSED, VisitPhase.PAUSING, VisitPhase.ENDING)) return
        val clear = confidence < 0 || confidence >= .45f
        val intent = interpretVisitIntent(text, experience.hasArtwork, confirmingCapture)
        if (!final) {
            if (clear && spokenKey(text).startsWith("luma ") && intent.command in setOf(VisitCommand.QUIET, VisitCommand.PAUSE, VisitCommand.FINISH)) accept(intent, text, confidence)
            else if (clear && text.isNotBlank() && echoFor(text).isEmpty() && state.value.phase == VisitPhase.LISTENING) armIdle(false)
            return
        }
        accept(intent, text, confidence)
    }
    private fun accept(intent: VisitIntent, text: String, confidence: Float) {
        if (policy?.admit(text, now(), confidence, echoFor(text), allowUncertain = true) != true) return
        if (confidence >= 0 && confidence < .45f && intent.command !in setOf(VisitCommand.QUIET, VisitCommand.PAUSE, VisitCommand.FINISH)) {
            experience.interrupt()
            if (repairs++ >= 1) { pause(); return }
            runOperation { run -> say("No llegué a entenderte. ¿Podés decirlo de otra forma?", run); listenTurn(run) }
            return
        }
        repairs = 0; log("intent:${intent.command.name}")
        when (intent.command) {
            VisitCommand.FINISH -> finish()
            VisitCommand.PAUSE -> pause()
            VisitCommand.QUIET -> {
                confirmingCapture = false; experience.interrupt()
                runOperation { run -> openMic(run); listenTurn(run) }
            }
            else -> {
                experience.interrupt()
                runOperation { run ->
                    confirmingCapture = intent.command == VisitCommand.CLARIFY
                    when (intent.command) {
                        VisitCommand.CLARIFY -> say("¿Querés que mire una obra nueva?", run)
                        VisitCommand.HELP -> say("Podés explorar otra obra, preguntar por un detalle o pedirme que repita. Si querés silencio, pedime una pausa.", run)
                        VisitCommand.REPEAT -> {
                            if (experience.lastAnswer.isBlank()) { confirmingCapture = true; say("Todavía no elegimos una obra. ¿Querés que mire una?", run) }
                            else say(experience.lastAnswer, run)
                        }
                        VisitCommand.CAPTURE -> {
                            admitModelTurn(run); micOpen = false
                            publish(VisitPhase.CAPTURING, "Mirando la nueva obra · la escucha vuelve después de la foto", run)
                            experience.closeListening(); ensureCurrent(run)
                            experience.say("Voy a mirar.")
                            experience.selectArtwork(); ensureCurrent(run)
                            experience.cue(VisitCue.CAPTURED)
                            openMic(run)
                            publish(VisitPhase.THINKING, "Explorando la obra · podés decir pausa o terminar", run)
                            val answer = experience.describeArtwork(); ensureCurrent(run); say(answer, run)
                        }
                        VisitCommand.QUESTION -> {
                            if (!experience.artworkFresh) { confirmingCapture = true; say("Necesito mirar la obra para responderte. ¿Querés que la capture?", run) }
                            else {
                                admitModelTurn(run)
                                publish(VisitPhase.THINKING, "Pensando en tu pregunta · podés interrumpir", run)
                                val answer = experience.askArtwork(intent.question.ifBlank { text }); ensureCurrent(run); say(answer, run)
                            }
                        }
                        else -> Unit
                    }
                    listenTurn(run)
                }
            }
        }
    }
    private fun admitModelTurn(run: Run) {
        check(policy?.modelTurn(now()) == true) { "Llegamos al límite de consultas de esta visita" }
        publish(state.value.phase, state.value.status, run)
    }

    fun pause(automatic: Boolean = false) {
        if (!state.value.active || state.value.phase in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING, VisitPhase.ENDING)) return
        confirmingCapture = false; resumeAfterPause = false
        experience.interrupt(); experience.stopListening(); micOpen = false
        // Publish before waiting for the previous capture's NonCancellable SDK cleanup.
        mutable.update { it.copy(phase = VisitPhase.PAUSING, listening = false, status = "Cerrando la escucha…") }
        runOperation { run ->
            experience.closeListening(); ensureCurrent(run)
            ownSpeech = ""; speechEndedAt = Long.MIN_VALUE
            experience.cue(VisitCue.PAUSED)
            ensureCurrent(run)
            publish(VisitPhase.PAUSED, "En pausa · conservamos la obra. Volvé con Hey Meta", run)
            log("pause:confirmed,automatic=$automatic")
            if (resumeAfterPause) resume()
        }
    }
    private fun finish(farewell: String = "Terminamos la visita. Hasta la próxima.") {
        if (!state.value.active || state.value.phase == VisitPhase.ENDING) return
        experience.interrupt(); experience.stopListening(); micOpen = false
        confirmingCapture = false; resumeAfterPause = false; experience.forget()
        runOperation { run ->
            publish(VisitPhase.ENDING, "Cerrando la visita y olvidando la obra…", run)
            experience.closeListening(); ensureCurrent(run)
            experience.say(farewell)
            if (current(run)) stop("Visita terminada. Obra y conversación liberadas.")
        }
    }
    private fun fail(message: String) {
        if (!state.value.active) return
        if (state.value.phase == VisitPhase.ENDING) { stop(message.take(400)); return }
        log("visit:failed")
        experience.interrupt(); experience.stopListening(); micOpen = false
        confirmingCapture = false; resumeAfterPause = false; experience.forget()
        mutable.update { it.copy(phase = VisitPhase.ENDING, listening = false, hasArtwork = false, status = "Cerrando la visita…") }
        runOperation { run ->
            try {
                val closed = try {
                    withTimeoutOrNull(5000) { experience.closeListening(); true } == true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { false }
                ensureCurrent(run)
                // Speak only after confirmed mic closure. Failure of the notice cannot recurse.
                if (closed) {
                    try { withTimeoutOrNull(4000) { experience.say("No pude continuar la visita. Volvé a llamar a Luma cuando quieras.") } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { }
                }
            } finally {
                if (current(run)) stop(message.take(400))
            }
        }
    }
    /** Immediate user/lifecycle stop; adapter keeps closing ownership until SDK STOPPED. */
    fun stop(message: String = "Visita finalizada. Obra y conversación liberadas.") {
        stopInternal(message, notifyEnded = true)
    }
    private fun stopInternal(message: String, notifyEnded: Boolean) {
        val used = policy?.turns ?: state.value.turns
        session++; serial++; cancelIdle(); operation?.cancel(); operation = null
        lifetimeJob?.cancel(); lifetimeJob = null
        experience.interrupt(); experience.stopListening(); experience.forget(); experience.ownVisit(false)
        micOpen = false; policy = null; confirmingCapture = false; repairs = 0; resumeAfterPause = false
        ownSpeech = ""; speechEndedAt = Long.MIN_VALUE
        mutable.value = VisitState(status = message, turns = used, idleSeconds = state.value.idleSeconds)
        val callback = ended; ended = null; if (notifyEnded) callback?.invoke(); log("visit:stopped")
    }
    companion object {
        const val MAX_VISIT_MS = 600_000L
        private const val MAX_UNBROKEN_LISTEN_MS = 120_000L
    }
}
