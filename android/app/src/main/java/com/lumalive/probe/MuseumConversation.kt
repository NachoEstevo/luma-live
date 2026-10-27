package com.lumalive.probe

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

data class MuseumUiState(
    val hasArtwork: Boolean = false, val busy: Boolean = false, val listening: Boolean = false,
    val status: String = "Elegí una obra desde la cámara de los lentes", val transcript: String = "",
    val metrics: String = "Sin consultas a Muse", val voiceReady: Boolean = false,
)

class MuseumConversation(context: Context, private val camera: GlassesCameraController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = MuseumApi()
    private val microphone = GlassesMicrophone(context)
    private val history = MuseumHistory()
    private val mutable = MutableStateFlow(MuseumUiState())
    val state = mutable.asStateFlow()
    private var image: String? = null
    private var capturedAt = 0L
    private var task: Job? = null
    private var epoch = 0
    private var lastAnswer = ""
    val narrator = MuseumNarrator(context)
    var visitOwned = false
        private set

    init {
        scope.launch { narrator.state.collect { voice -> mutable.update { it.copy(voiceReady = voice.ready) } } }
    }

    fun notice(text: String) { mutable.update { it.copy(status = text) } }
    fun finishVoice() { microphone.finishTurn() }
    fun repeatAnswer() { if (lastAnswer.isNotEmpty()) runTask { narrator.say(lastAnswer) } }
    fun setVisitOwned(value: Boolean) { visitOwned = value }
    fun stopSpeaking() { narrator.stop() }
    fun interrupt() { api.cancel(); narrator.stop() }
    suspend fun say(text: String) { narrator.say(text) }
    fun answerToRepeat() = lastAnswer.ifEmpty { "Todavía no exploramos una obra. Decime: mirá esta obra." }
    fun hasFreshArtwork() = image != null && System.currentTimeMillis() - capturedAt in 0..600_000
    suspend fun answerForVisit(question: String) = askSelected(question, readAloud = false)
    suspend fun selectForVisit() { selectImage() }
    suspend fun describeForVisit() = askSelected(FIRST_LOOK, readAloud = false)

    fun checkBackend() = runTask {
        val config = api.check()
        notice("Backend conectado · ${config.getString("model")} · ${config.getJSONObject("gate").getInt("remaining")} solicitudes disponibles")
    }

    fun selectArtwork() {
        if (!camera.state.value.streaming) { notice("Primero iniciá la cámara y apuntá a una obra"); return }
        runTask { selectImage(); askSelected(FIRST_LOOK) }
    }

    private suspend fun selectImage() {
            notice("Capturando obra desde los lentes…")
            val bitmap = camera.captureArtwork()
            val encoded = withContext(Dispatchers.Default) {
                val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
                val resized = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                val bytes = ByteArrayOutputStream().apply { resized.compress(Bitmap.CompressFormat.JPEG, 75, this) }.toByteArray()
                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            check(encoded.length <= 2_000_000) { "La imagen es demasiado grande" }
            image = encoded; capturedAt = System.currentTimeMillis(); history.clear(); lastAnswer = ""
            camera.stop("Obra seleccionada; cerrando cámara para conversar")
            withTimeout(5000) { camera.state.first { !it.hasSession } }
            mutable.update { it.copy(hasArtwork = true, transcript = "", status = "Consultando la obra a Muse…") }
    }

    fun askText(question: String) {
        if (question.isBlank() || question.length > 1000) { notice("Escribí una pregunta de hasta 1000 caracteres"); return }
        runTask { askSelected(question.trim()) }
    }

    fun askVoice() = runTask {
        check(image != null) { "Primero seleccioná una obra" }
        check(!camera.state.value.hasSession) { "Detené la cámara antes de abrir el micrófono de los lentes" }
        narrator.stop()
        notice("Preparando micrófono Bluetooth…")
        val wav = microphone.record { device ->
            mutable.update { it.copy(listening = true, status = "Hablá ahora · $device · máximo 12 s. Tocá Terminar pregunta al finalizar.") }
            Log.i("LumaMuseum", "microphone:bluetooth_verified")
        }
        mutable.update { it.copy(listening = false, status = "Transcribiendo pregunta con Muse Voice…") }
        val transcription = api.transcribe(Base64.encodeToString(wav, Base64.NO_WRAP))
        Log.i("LumaMuseum", "asr:completed,duration_ms=${transcription.optLong("audioDurationMs")}")
        askSelected(transcription.getString("text"))
    }

    private suspend fun askSelected(question: String, readAloud: Boolean = true): String {
        val selected = checkNotNull(image) { "Primero seleccioná una obra" }
        check(System.currentTimeMillis() - capturedAt <= 600_000) { "La referencia venció; seleccioná la obra otra vez" }
        narrator.stop()
        notice("Muse está preparando la respuesta…")
        val started = SystemClock.elapsedRealtime()
        val result = api.ask(selected, capturedAt, question, history.snapshot())
        check(result.optString("mode") == "meta") { "La respuesta no provino de Muse" }
        val answer = result.getString("text")
        history.remember(question, answer); lastAnswer = answer
        val roundTrip = SystemClock.elapsedRealtime() - started
        val cost = if (result.isNull("estimatedUsd")) "desconocido" else String.format(Locale.US, "US$ %.5f", result.getDouble("estimatedUsd"))
        mutable.update { it.copy(transcript = (it.transcript + "\nVos: $question\nLuma: $answer\n").takeLast(12000), status = "Respuesta real de Muse · obra seleccionada, cámara cerrada", metrics = "${result.optString("model")} · ${roundTrip} ms · estimación de esta respuesta $cost\n${result.getJSONObject("gate").getInt("remaining")} solicitudes restantes (visión + transcripción)") }
        Log.i("LumaMuseum", "vision:completed,round_trip_ms=$roundTrip,input_tokens=${result.optJSONObject("usage")?.optLong("input_tokens")},output_tokens=${result.optJSONObject("usage")?.optLong("output_tokens")}")
        if (readAloud) narrator.say(answer)
        return answer
    }

    private fun runTask(action: suspend () -> Unit) {
        if (state.value.busy || visitOwned) return
        val current = epoch
        task = scope.launch {
            mutable.update { it.copy(busy = true) }
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == epoch && currentCoroutineContext().isActive) {
                    notice(e.message?.take(600) ?: "No se completó la consulta. Revisá USB/backend y volvé a intentar.")
                    Log.i("LumaMuseum", "operation:failed,type=${e.javaClass.simpleName}")
                }
            } finally {
                if (current == epoch) mutable.update { it.copy(busy = false, listening = false) }
            }
        }
    }

    fun pause() {
        epoch++; task?.cancel(); api.cancel(); microphone.finishTurn(); narrator.stop()
        image = null; history.clear(); lastAnswer = ""; capturedAt = 0
        mutable.update { MuseumUiState(status = "Pausado. Imagen y conversación liberadas; micrófono y voz detenidos.", voiceReady = it.voiceReady) }
    }
    fun close() { pause(); narrator.close(); scope.cancel() }

    companion object {
        private const val FIRST_LOOK = "Presentame esta obra como si la exploráramos juntos. Empezá por la escena general y un detalle que valga la pena descubrir."
    }
}
