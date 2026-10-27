package com.lumalive.probe

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class NarratorState(
    val ready: Boolean = false,
    val speaking: Boolean = false,
    val voiceLabel: String = "Preparando voz",
    val error: String = "",
)

/** Owns only local speech. The conversation owns text history and microphone timing. */
class MuseumNarrator(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(NarratorState())
    val state: StateFlow<NarratorState> = mutable.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private var engine: TextToSpeech? = null
    private var profiles = emptyList<NarratorVoice>()
    private var voices = emptyMap<String, Voice>()
    private var selectedIndex = 0
    private var sequence = 0L
    private var closed = false
    private data class Utterance(val id: String, val done: CompletableDeferred<Unit>)
    private var pending: Utterance? = null
    @Volatile private var spokenText = ""
    val currentText: String get() = spokenText

    init {
        onMain {
            if (!closed) {
                try {
                    val googleInstalled = appContext.packageManager
                        .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                        .any { it.serviceInfo?.packageName == GOOGLE_ENGINE }
                    // Post even a main-thread init callback: constructor assignment must finish first.
                    val listener = TextToSpeech.OnInitListener { result -> main.post { initialize(result) } }
                    engine = if (googleInstalled) TextToSpeech(appContext, listener, GOOGLE_ENGINE)
                        else TextToSpeech(appContext, listener)
                } catch (_: Exception) {
                    initializationFailed("No se pudo abrir la voz de Android. Revisá el motor de texto a voz en Ajustes.")
                }
            }
        }
    }

    private fun initialize(result: Int) {
        if (closed || initialized.isCompleted) return
        val tts = engine
        if (result != TextToSpeech.SUCCESS || tts == null) {
            initializationFailed("El motor de voz no inició. Revisá texto a voz en Ajustes y volvé a abrir Luma.")
            return
        }
        try {
            voices = tts.voices.orEmpty().associateBy { it.name }
            profiles = NarratorVoicePolicy.profiles(voices.values.map {
                NarratorVoice(it.name, it.locale.toLanguageTag(), it.quality, it.latency,
                    it.isNetworkConnectionRequired, !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            })
            check(profiles.isNotEmpty()) { "Instalá una voz de español sin conexión en los ajustes de texto a voz y volvé a abrir Luma." }
            check(tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) == TextToSpeech.SUCCESS) {
                "No se pudo preparar la salida de voz de Android. Volvé a abrir Luma."
            }
            tts.setPitch(1.0f)
            tts.setSpeechRate(0.96f)
            check(tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onMain {
                    if (pending?.id == utteranceId) mutable.value = mutable.value.copy(speaking = true)
                }
                override fun onDone(utteranceId: String?) = onMain { finish(utteranceId) }
                @Deprecated("Android callback")
                override fun onError(utteranceId: String?) = onMain { speechFailed(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) = onMain { speechFailed(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) = onMain {
                    finish(utteranceId, CancellationException("Lectura detenida"))
                }
            }) == TextToSpeech.SUCCESS) { "No se pudo preparar el seguimiento de la voz. Volvé a abrir Luma." }
            selectedIndex = profiles.indices.firstOrNull { setVoice(it) }
                ?: error("No se pudo cargar una voz de español sin conexión. Revisá las voces instaladas en Ajustes.")
            mutable.value = mutable.value.copy(ready = true, error = "")
            initialized.complete(Unit)
        } catch (error: Exception) {
            initializationFailed(error.message ?: "No se pudo preparar una voz de español sin conexión.")
        }
    }

    private fun initializationFailed(message: String) {
        if (closed) return
        mutable.value = mutable.value.copy(ready = false, speaking = false, voiceLabel = "Voz no disponible", error = message)
        initialized.completeExceptionally(IllegalStateException(message))
    }

    private fun setVoice(index: Int): Boolean {
        val profile = profiles[index]
        val voice = voices[profile.name] ?: return false
        if (engine?.setVoice(voice) != TextToSpeech.SUCCESS) return false
        mutable.value = mutable.value.copy(voiceLabel = NarratorVoicePolicy.label(profile))
        return true
    }

    /** Returns only after speech finishes. Cancellation and stop release the caller immediately. */
    suspend fun say(text: String): Unit = withContext(Dispatchers.Main.immediate) {
        check(!closed) { "La voz está cerrada. Volvé a abrir Luma." }
        require(text.isNotBlank()) { "No hay texto para leer." }
        require(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "La respuesta es demasiado larga para leerla. Pedí una versión más breve." }
        stopCurrent()
        val request = Utterance("luma-narrator-${++sequence}", CompletableDeferred())
        pending = request
        mutable.value = mutable.value.copy(error = "")
        try {
            try {
                withTimeout(15_000) {
                    select<Unit> {
                        initialized.onAwait { }
                        request.done.onAwait { }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                error("La voz de Android tardó demasiado en iniciar. Revisá texto a voz en Ajustes y volvé a abrir Luma.")
            }
            check(pending === request && !closed) { "La lectura ya no está activa." }
            spokenText = text
            mutable.value = mutable.value.copy(speaking = true)
            check(engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, request.id) == TextToSpeech.SUCCESS) {
                "Android no pudo leer la respuesta. Revisá la voz sin conexión y el volumen multimedia."
            }
            try {
                withTimeout((text.length * 100L + 10_000).coerceIn(15_000, 120_000)) { request.done.await() }
            } catch (_: TimeoutCancellationException) {
                error("La lectura no terminó a tiempo. Revisá la salida de audio y volvé a intentar.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (pending === request) mutable.value = mutable.value.copy(error = error.message ?: "Falló la lectura de Android.")
            throw error
        } finally {
            if (pending === request) stopCurrent()
        }
    }

    private fun speechFailed(id: String?) {
        finish(id, IllegalStateException("Android interrumpió la lectura. Revisá la voz sin conexión y el volumen multimedia."))
    }

    private fun finish(id: String?, error: Exception? = null) {
        val request = pending ?: return
        if (request.id != id) return
        pending = null
        spokenText = ""
        mutable.value = mutable.value.copy(speaking = false,
            error = if (error != null && error !is CancellationException) error.message.orEmpty() else mutable.value.error)
        if (error == null) request.done.complete(Unit) else request.done.completeExceptionally(error)
    }

    private fun stopCurrent() {
        val request = pending
        pending = null // Ignore synchronous or late onStop/onDone callbacks from this utterance.
        spokenText = ""
        engine?.stop()
        mutable.value = mutable.value.copy(speaking = false)
        request?.done?.cancel(CancellationException("Lectura detenida"))
    }

    fun stop() = onMain { if (!closed) stopCurrent() }

    fun cycleVoice() = onMain {
        if (!closed && mutable.value.ready) {
            if (profiles.size < 2) {
                mutable.value = mutable.value.copy(error = "Solo hay una variante de español sin conexión instalada. Podés agregar otra en Ajustes de texto a voz.")
            } else {
                stopCurrent()
                val next = (1 until profiles.size).map { (selectedIndex + it) % profiles.size }.firstOrNull { setVoice(it) }
                if (next != null) {
                    selectedIndex = next
                    mutable.value = mutable.value.copy(error = "")
                } else mutable.value = mutable.value.copy(error = "No se pudo cargar otra voz. Revisá las voces descargadas en Ajustes.")
            }
        }
    }

    fun close() = onMain {
        if (!closed) {
            closed = true
            stopCurrent()
            initialized.completeExceptionally(IllegalStateException("La voz está cerrada. Volvé a abrir Luma."))
            engine?.shutdown()
            engine = null
            spokenText = ""
            voices = emptyMap()
            profiles = emptyList()
            mutable.value = mutable.value.copy(ready = false, speaking = false)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private companion object { const val GOOGLE_ENGINE = "com.google.android.tts" }
}
