package com.lumalive.probe

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** SDK/audio effects, shared by the visit controller rather than the Activity. */
class SdkVisitExperience(context: Context, private val camera: GlassesCameraController, private val museum: MuseumConversation) : VisitExperience {
    private val speech = GlassesSpeech()
    private val cues = VisitCues(context)
    override val readyForVisit get() = camera.state.value.registered && camera.state.value.activeDevice && !camera.state.value.hasSession && !museum.state.value.busy
    override val hasArtwork get() = museum.state.value.hasArtwork
    override val artworkFresh get() = museum.hasFreshArtwork()
    override val lastAnswer get() = museum.answerToRepeat().takeIf { hasArtwork }.orEmpty()
    override fun ownVisit(owned: Boolean) = museum.setVisitOwned(owned)
    override suspend fun openListening(onText: (String, Boolean, Float) -> Unit, onFailure: (String) -> Unit) {
        withTimeout(5000) { camera.state.first { !it.hasSession } }
        speech.open(onText, onFailure)
    }
    override suspend fun closeListening() { speech.closeForCapture() }
    override fun stopListening() = speech.stopNow()
    override suspend fun selectArtwork() {
        check(camera.state.value.registered && camera.state.value.activeDevice) { "Los lentes no están conectados" }
        camera.start()
        try {
            val ready = withTimeout(20_000) { camera.state.first { it.streaming || !it.hasSession } }
            check(ready.streaming) { "No se pudo abrir la cámara: ${ready.message}" }
            museum.selectForVisit()
        } finally {
            camera.stop("Captura de la obra terminada")
            withContext(NonCancellable) { withTimeout(5000) { camera.state.first { !it.hasSession } } }
        }
    }
    override suspend fun describeArtwork() = museum.describeForVisit()
    override suspend fun askArtwork(question: String) = museum.answerForVisit(question)
    override suspend fun say(text: String) = museum.say(text)
    override suspend fun cue(cue: VisitCue) {
        check(cues.play(cue)) { "No se pudo reproducir la señal de Luma" }
    }
    override fun interrupt() = museum.interrupt()
    override fun forget() { camera.stop("Visita finalizada"); museum.pause() }
}
