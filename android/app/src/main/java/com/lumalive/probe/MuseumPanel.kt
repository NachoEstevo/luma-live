package com.lumalive.probe

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*

class MuseumPanel(context: Context, private val museum: MuseumConversation, private val requestVoice: () -> Unit, private val pauseAll: () -> Unit) : LinearLayout(context) {
    private val consent: CheckBox
    private val description: TextView
    private val transcript: TextView
    private val metrics: TextView
    private val input: EditText
    private val select: Button
    private val ask: Button
    private val voice: Button
    private var cameraStreaming = false
    private var current = MuseumUiState()

    init {
        orientation = VERTICAL
        fun label(text: String, size: Float = 16f) = TextView(context).apply { this.text = text; textSize = size; setPadding(0, 16, 0, 8); addView(this) }
        fun button(text: String, action: () -> Unit) = Button(context).apply { this.text = text; setOnClickListener { action() }; addView(this, LayoutParams(-1, -2)) }
        label("Luma Museo · demo real", 25f)
        label("Seleccioná una obra desde la cámara. Después conversamos sobre esa imagen con la cámara apagada. La identificación visual puede ser tentativa.")
        consent = CheckBox(context).apply {
            text = "Autorizo enviar la obra y las preguntas de voz a Meta y consumir la API. Los créditos activos pueden permitir uso para mejorar productos. Sin grabación de archivos."
            addView(this)
            setOnCheckedChangeListener { _, checked -> if (!checked) museum.pause(); render(current) }
        }
        button("Comprobar backend USB") { museum.checkBackend() }
        select = button("Observar esta obra con Muse") { museum.selectArtwork() }
        input = EditText(context).apply { hint = "Pregunta sobre la obra seleccionada"; isSaveEnabled = false; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; contentDescription = "Pregunta para Luma"; addView(this, LayoutParams(-1, -2)) }
        ask = button("Preguntar y escuchar respuesta") { museum.askText(input.text.toString()) }
        voice = button("Hablar desde los lentes") { if (current.listening) museum.finishVoice() else requestVoice() }
        button("Repetir última respuesta") { museum.repeatAnswer() }
        button("Pausar Luma y olvidar obra") { pauseAll() }
        description = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        metrics = label("")
        transcript = label("").apply { setTextIsSelectable(true); isSaveEnabled = false }
        render(current)
    }

    fun cameraChanged(streaming: Boolean) { cameraStreaming = streaming; render(current) }
    fun clearPrivateViews() { input.setText(""); transcript.text = ""; current = MuseumUiState() }
    fun render(state: MuseumUiState) {
        current = state
        select.isEnabled = consent.isChecked && cameraStreaming && !state.busy
        ask.isEnabled = consent.isChecked && state.hasArtwork && !state.busy
        voice.isEnabled = consent.isChecked && state.hasArtwork && (!state.busy || state.listening)
        voice.text = if (state.listening) "Terminar pregunta y enviarla" else "Hablar desde los lentes"
        if (description.text.toString() != state.status) description.text = state.status
        metrics.text = state.metrics; transcript.text = state.transcript
    }
}
