package com.lumalive.probe

import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun confirmSpeechStopped(state: StateFlow<DeviceSessionState>, timeoutMs: Long = 8000) {
    withTimeout(timeoutMs) { state.first { it == DeviceSessionState.STOPPED } }
}

/** Owns one on-glasses Speech session. No phone microphone or audio upload fallback. */
class GlassesSpeech {
    private var session: DeviceSession? = null
    private var speech: Speech? = null
    private var jobs: CoroutineScope? = null
    private var generation = 0
    private var closing: DeviceSession? = null
    private val opening = Mutex()

    suspend fun open(onText: (String, Boolean, Float) -> Unit, onFailure: (String) -> Unit) = opening.withLock {
        awaitClosing()
        check(session == null) { "Ya hay una sesión de escucha" }
        val run = ++generation
        var creationError = "No se pudo abrir la escucha de los lentes"
        var created: DeviceSession? = null
        Wearables.createSession(AutoDeviceSelector()).fold(onSuccess = { created = it }, onFailure = { error, _ -> creationError = error.name })
        val current = checkNotNull(created) { creationError }
        session = current
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        jobs = scope
        scope.launch {
            current.errors.collect { error ->
                Log.i("LumaVisit", "speech_session:error=${error.name}")
                if (run == generation && error.name != "DWA_OUT_OF_STU_RANGE") onFailure("Los lentes no habilitaron escucha: ${error.name}")
            }
        }
        try {
            current.start()
            withTimeout(15_000) { current.state.first { it == DeviceSessionState.STARTED } }
            var attached: Speech? = null
            current.addSpeech().fold(onSuccess = { attached = it }, onFailure = { error, _ -> creationError = error.name })
            val recognizer = checkNotNull(attached) { "Speech no disponible: $creationError" }
            speech = recognizer
            scope.launch {
                recognizer.transcriptions.collect { result ->
                    if (result != null && run == generation) onText(result.text, result.isFinal, result.confidence)
                }
            }
            scope.launch {
                recognizer.errors.collect { error ->
                    if (error != null && run == generation) {
                        Log.i("LumaVisit", "speech:error=${error.name}")
                        onFailure("El reconocimiento de los lentes se detuvo: ${error.name}")
                    }
                }
            }
            scope.launch {
                recognizer.locale.collect { locale ->
                    if (!locale.isNullOrBlank()) Log.i("LumaVisit", "speech:locale=$locale")
                }
            }
            scope.launch {
                var listening = false
                recognizer.state.collect { state ->
                    Log.i("LumaVisit", "speech:state=${state.name}")
                    if (state == SpeechState.STARTED) listening = true
                    if (listening && state == SpeechState.STOPPED && run == generation) onFailure("Los lentes terminaron la escucha. Volvé a iniciar Luma.")
                }
            }
            recognizer.start().onFailure { error, _ -> throw IllegalStateException("Speech: ${error.name}") }
            withTimeout(10_000) { recognizer.state.first { it == SpeechState.STARTED } }
        } catch (e: Exception) {
            stopNow()
            throw e
        }
    }

    suspend fun closeForCapture() {
        stopNow()
        awaitClosing()
    }

    private suspend fun awaitClosing() {
        val previous = closing ?: return
        confirmSpeechStopped(previous.state)
        if (closing === previous) closing = null
    }

    fun stopNow() {
        ++generation
        speech?.stop()
        session?.let { closing = it; it.stop() }
        jobs?.cancel()
        speech = null; session = null; jobs = null
    }
}
