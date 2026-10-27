package com.lumalive.probe

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.Log
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtime get() = application as LumaApplication
    private val controller get() = runtime.camera
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var preview: ImageView
    private lateinit var photo: ImageView
    private lateinit var register: Button
    private lateinit var start: Button
    private lateinit var capture: Button
    private lateinit var stop: Button
    private var pendingExport: String? = null
    private var cameraRequestPending = false
    private lateinit var museum: MuseumConversation
    private lateinit var museumPanel: MuseumPanel
    private lateinit var visitStatus: TextView
    private lateinit var visitPhase: TextView
    private lateinit var connectionSummary: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var launchStatus: TextView
    private lateinit var visitStart: Button
    private lateinit var visitEnd: Button
    private lateinit var visitPause: Button
    private lateinit var idleWindow: Button
    private lateinit var voiceChange: Button
    private lateinit var voiceSample: Button
    private lateinit var consent: CheckBox
    private var pendingVisit = false
    private var voiceIntentHandled = false

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPreparedVisit()
        else { pendingVisit = false; visitStatus.text = "Habilitá notificaciones de Luma para tener un control visible de terminar visita con el teléfono guardado." }
    }
    private val speechPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        result.fold(onSuccess = { value ->
            if (value == PermissionStatus.Granted && pendingVisit) ensureNotificationAndStart()
            else { pendingVisit = false; museum.notice("Sin permiso de escucha de los lentes. Podés seguir con la prueba manual.") }
        }, onFailure = { error, _ -> pendingVisit = false; museum.notice("Permiso de escucha: ${error.name}") })
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) museum.askVoice()
        else museum.notice("Micrófono sin autorizar. Podés seguir preguntando por texto.")
    }

    private val nearbyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) initialize() else controller.message("Permiso de dispositivos cercanos denegado. Habilitalo en Ajustes para conectar los lentes.")
    }
    private val cameraPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        cameraRequestPending = false
        result.fold(
            onSuccess = { value ->
                if (value == PermissionStatus.Granted && pendingVisit) ensureSpeechPermission()
                else if (value == PermissionStatus.Granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
                else controller.message("Cámara sin autorizar o app fuera de primer plano. Volvé a tocar Probar cámara.")
            },
            onFailure = { error, _ -> controller.message("Permiso de lentes: ${error.description}") },
        )
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingExport; pendingExport = null
        if (uri != null && csv != null) {
            runCatching { checkNotNull(contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(csv) } }
                .onSuccess { controller.message("Métricas exportadas. El CSV no contiene imágenes ni identificadores.") }
                .onFailure { controller.message("No se pudo guardar el CSV") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voiceIntentHandled = savedInstanceState?.getBoolean("voice_intent_handled") ?: false
        museum = runtime.museum
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        var target = layout
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(24), bars.top + dp(20), dp(24), bars.bottom + dp(24)); insets
        }
        fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(28, 51, 47)); includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(8), 0, dp(8)); target.addView(this) }
        fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
            this.text = text; minHeight = dp(56); textSize = 16f; isAllCaps = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            val fill = ColorStateList(states, intArrayOf(Color.rgb(227, 233, 229), if (primary) Color.rgb(28, 73, 61) else Color.WHITE))
            val shape = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(fill); if (!primary) setStroke(dp(1), Color.rgb(178, 195, 186)) }
            background = RippleDrawable(ColorStateList.valueOf(Color.rgb(194, 216, 202)), shape, null)
            backgroundTintList = null
            setTextColor(ColorStateList(states, intArrayOf(Color.rgb(93, 111, 102), if (primary) Color.WHITE else Color.rgb(28, 73, 61))))
            setOnClickListener { action() }
            target.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        label("Luma Museo", 20f).apply { setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true }
        connectionSummary = label("Conectando tus lentes…", 14f)
        label("El arte, a tu ritmo.", 32f).apply { typeface = Typeface.create("serif", Typeface.NORMAL); isAccessibilityHeading = true; setPadding(0, dp(24), 0, dp(8)) }
        label("Mirá una obra. Descubrila conversando.", 17f)
        val conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(230, 238, 232)) }
        }
        layout.addView(conversation, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }); target = conversation
        visitPhase = label("Lista para acompañarte", 21f).apply { setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true }
        visitStatus = label(runtime.guide.state.value.status, 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        visitStart = button("Empezar conversación", true) { prepareVisit() }.apply { isEnabled = runtime.voiceConsent }
        visitPause = button("Pausar escucha") { runtime.guide.pause() }.apply { visibility = View.GONE; isEnabled = false }
        visitEnd = button("Terminar y olvidar obra") { runtime.guide.stop("Visita terminada por vos") }.apply { visibility = View.GONE; isEnabled = false }
        target = layout
        consent = CheckBox(this).apply {
            text = "Permitir conversaciones por voz"; textSize = 16f; minHeight = dp(56)
            isSaveEnabled = false; isChecked = runtime.voiceConsent; target.addView(this)
            setOnCheckedChangeListener { _, checked -> runtime.voiceConsent = checked; if (!checked) { runtime.guide.stop("Visitas por voz deshabilitadas"); museumPanel.clearPrivateViews() }; visitStart.isEnabled = checked && (!runtime.guide.state.value.active || runtime.guide.state.value.phase == VisitPhase.PAUSED) }
        }
        label("Al pedir una obra, enviamos su imagen y tus preguntas a Meta; consume API y sus condiciones de créditos pueden permitir mejorar productos. Escucha por turnos, hasta 8 consultas. El contexto vence a los 10 minutos y se borra al terminar.", 14f)
        val preferences = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val preferencesToggle = button("Ajustar voz y tiempo de respuesta") { }
        preferencesToggle.setOnClickListener {
            val expanded = preferences.visibility != View.VISIBLE
            preferences.visibility = if (expanded) View.VISIBLE else View.GONE
            preferencesToggle.text = if (expanded) "Cerrar ajustes de conversación" else "Ajustar voz y tiempo de respuesta"
            androidx.core.view.ViewCompat.setStateDescription(preferencesToggle, if (expanded) "Expandido" else "Contraído")
        }
        layout.addView(preferences); target = preferences
        idleWindow = button("Tiempo para responder: ${runtime.idleSeconds} s de silencio") {
            runtime.idleSeconds = when (runtime.idleSeconds) { 25 -> 45; 45 -> 60; else -> 25 }
            idleWindow.text = "Tiempo para responder: ${runtime.idleSeconds} s de silencio"
        }
        voiceStatus = label("Preparando tu voz…")
        voiceChange = button("Cambiar voz española") { if (!runtime.guide.state.value.active) museum.narrator.cycleVoice() }
        voiceSample = button("Escuchar muestra de voz") {
            if (!runtime.guide.state.value.active) lifecycleScope.launch {
                runCatching { museum.say("Hola, soy Luma. Vamos a explorar esta obra juntos. Primero te cuento la escena; después seguimos el detalle que más te interese.") }.onFailure { museum.notice(it.message ?: "No se pudo reproducir la muestra") }
            }
        }
        launchStatus = label("Hey Meta · configurando", 14f)
        target = layout
        val help = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val helpToggle = button("Cómo conversar desde los lentes") { }
        helpToggle.setOnClickListener {
            val expanded = help.visibility != View.VISIBLE
            help.visibility = if (expanded) View.VISIBLE else View.GONE
            helpToggle.text = if (expanded) "Cerrar ayuda de voz" else "Cómo conversar desde los lentes"
            androidx.core.view.ViewCompat.setStateDescription(helpToggle, if (expanded) "Expandido" else "Contraído")
        }
        layout.addView(help); target = help
        label("Decí «Hey Meta, start Luma Museo». Cuando suene la señal ascendente, Luma te escucha.", 16f)
        label("«Contame qué ves»\n«¿Qué hay al fondo?»\n«No escuché bien»\n«Esperame un momento»\n«Terminemos la visita»", 17f)
        label("La señal descendente confirma la pausa: micrófono cerrado y obra conservada. Volvé con Hey Meta para retomar la misma obra.", 15f)
        target = layout
        label("Demo por USB · requiere esta PC\nLa cámara se abre al pedir una obra. Las respuestas describen esa captura.", 13f).apply { setPadding(0, dp(20), 0, dp(8)) }
        val diagnostics = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val diagnosticsToggle = button("Conectar lentes y abrir diagnóstico") { }
        diagnosticsToggle.setOnClickListener {
            val expanded = diagnostics.visibility != View.VISIBLE
            diagnostics.visibility = if (expanded) View.VISIBLE else View.GONE
            diagnosticsToggle.text = if (expanded) "Cerrar conexión y diagnóstico" else "Conectar lentes y abrir diagnóstico"
            androidx.core.view.ViewCompat.setStateDescription(diagnosticsToggle, if (expanded) "Expandido" else "Contraído")
        }
        layout.addView(diagnostics); target = diagnostics
        label("Conexión y diagnóstico", 24f)
        status = label("Preparando")
        message = label("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        button("Cómo activar Developer Mode") {
            AlertDialog.Builder(this).setTitle("En la app Meta AI")
                .setMessage("Settings → App info → tocá App version cinco veces → Developer Mode → Enable.\n\nDespués volvé a Luma y tocá Vincular con Meta AI.")
                .setPositiveButton("Abrir Meta AI") { _, _ ->
                    val launch = packageManager.getLaunchIntentForPackage("com.facebook.stella")
                    if (launch != null) startActivity(launch) else controller.message("Abrí Meta AI desde las aplicaciones del teléfono")
                }.setNegativeButton("Cerrar", null).show()
        }
        button("1 · Preparar Bluetooth") { prepare() }
        register = button("2 · Vincular con Meta AI") {
            runCatching { Wearables.startRegistration(this) }.onFailure { controller.message("No se pudo abrir Meta AI; comprobá que esté instalada y Developer Mode activo.") }
        }
        button("Revisar / actualizar SDK de los lentes") {
            if (controller.state.value.initialized) Wearables.openDATGlassesAppUpdate(this)
                .onFailure { error, _ -> controller.message("Abrir SDK de lentes: ${error.description}") }
            else controller.message("Primero prepará Bluetooth")
        }
        start = button("3 · Probar cámara (máx. 2 min)") { requestCamera() }
        stop = button("Detener cámara y Luma") { pauseAll() }
        preview = ImageView(this).apply { contentDescription = "Vista en vivo desde la cámara de los lentes"; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(0xFF172628.toInt()) }
        target.addView(preview, LinearLayout.LayoutParams(-1, dp(240)))
        museumPanel = MuseumPanel(this, museum, ::requestMicrophone, ::pauseAll)
        target.addView(museumPanel)
        capture = button("Capturar foto solo en memoria") { controller.capture() }
        photo = ImageView(this).apply { contentDescription = "Última captura de los lentes, solo en memoria"; adjustViewBounds = true; maxHeight = 400 }
        target.addView(photo, LinearLayout.LayoutParams(-1, -2))
        button("Exportar métricas (CSV)") {
            pendingExport = controller.exportCsv()
            if (pendingExport == null) controller.message("Primero iniciá una sesión de cámara") else export.launch("luma-camera-metrics.csv")
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { controller.state.collect { render(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { museum.state.collect { museumPanel.render(it) } }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { runtime.guide.state.collect { value ->
                    visitStatus.text = value.status
                    visitPhase.text = when (value.phase) {
                        VisitPhase.IDLE -> "Lista para acompañarte"
                        VisitPhase.CONNECTING -> "Preparando la conversación"
                        VisitPhase.LISTENING -> "Te escucho"
                        VisitPhase.CAPTURING -> "Mirando la obra"
                        VisitPhase.THINKING -> "Explorando los detalles"
                        VisitPhase.SPEAKING -> "Luma está hablando"
                        VisitPhase.PAUSING -> "Cerrando la escucha"
                        VisitPhase.PAUSED -> "Tu obra sigue acá"
                        VisitPhase.ENDING -> "Terminando la visita"
                    }
                    visitStart.isEnabled = consent.isChecked && (!value.active || value.phase in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING))
                    visitStart.text = if (value.phase in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING)) "Retomar esta obra" else "Empezar conversación"
                    visitEnd.isEnabled = value.active
                    visitEnd.visibility = if (value.active) View.VISIBLE else View.GONE
                    visitPause.visibility = if (value.active && value.phase !in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING, VisitPhase.ENDING)) View.VISIBLE else View.GONE
                    visitPause.isEnabled = value.active && value.phase !in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING, VisitPhase.ENDING)
                    idleWindow.text = "Tiempo para responder: ${value.idleSeconds} s de silencio"
                    voiceChange.isEnabled = !value.active; voiceSample.isEnabled = !value.active
                } }
                launch { museum.narrator.state.collect { voice -> voiceStatus.text = if (voice.error.isNotBlank()) voice.error else voice.voiceLabel } }
                launch { runtime.launcher.state.collect { launchStatus.text = it } }
            }
        }
        prepare()
    }

    private fun prepare() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) nearbyPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else initialize()
    }

    private fun initialize() {
        runtime.initializeIfAllowed()
        if (controller.state.value.initialized) handleMetaIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        voiceIntentHandled = false
        if (controller.state.value.initialized) handleMetaIntent(intent)
    }

    private fun handleMetaIntent(incoming: Intent) {
        if (isVoiceInvocationsIntent(incoming)) {
            if (!voiceIntentHandled) {
                voiceIntentHandled = true
                Log.i("LumaVisit", "launch:validated_voice_intent")
                lifecycleScope.launch {
                    if (!runtime.startFromVoice()) visitStatus.text = museum.state.value.status
                }
            }
            return
        }
        Wearables.handleIntent(incoming) { request ->
            AlertDialog.Builder(this).setTitle("Conectar Luma con Meta AI")
                .setMessage("Meta AI solicita registrar esta app. La cámara se activa únicamente desde Probar cámara.")
                .setPositiveButton("Continuar") { _, _ -> request.continueRegistration(this).onFailure { error, _ -> controller.message(error.description) } }
                .setNegativeButton("Cancelar") { _, _ -> request.cancelRegistration() }
                .setOnCancelListener { request.cancelRegistration() }.show()
        }.onFailure { error, _ -> controller.message("Registro entrante: ${error.description}") }
    }

    private fun requestCamera() {
        if (runtime.guide.state.value.active) { museum.notice("Terminá la visita antes de usar el diagnóstico de cámara"); return }
        pendingVisit = false
        if (cameraRequestPending) return
        cameraRequestPending = true
        start.isEnabled = false
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA).fold(
                onSuccess = { value ->
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { cameraRequestPending = false; return@fold }
                    if (value == PermissionStatus.Granted) { cameraRequestPending = false; controller.start() } else cameraPermission.launch(Permission.CAMERA)
                },
                onFailure = { error, _ -> cameraRequestPending = false; controller.message("Comprobar cámara: ${error.description}") },
            )
        }
    }

    private fun render(state: ProbeUiState) {
        connectionSummary.text = when {
            !state.registered -> "Conectá Luma con Meta AI desde el diagnóstico"
            !state.activeDevice -> "Luma vinculada · esperando tus lentes"
            else -> "Tus lentes están conectados"
        }
        status.text = getString(R.string.probe_status, state.registration, state.device, state.session, state.stream, state.metrics)
        if (message.text.toString() != state.message) message.text = state.message
        register.isEnabled = state.initialized && !state.hasSession && !runtime.guide.state.value.active
        start.isEnabled = state.registered && state.activeDevice && !state.hasSession && !cameraRequestPending
        capture.isEnabled = state.streaming && !state.capturing
        stop.isEnabled = state.hasSession && !state.stopping
        preview.setImageBitmap(state.preview); photo.setImageBitmap(state.photo)
        museumPanel.cameraChanged(state.streaming)
    }

    private fun requestMicrophone() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) museum.askVoice()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun pauseAll() {
        runtime.guide.stop("Luma está en pausa")
        museum.pause(); museumPanel.clearPrivateViews()
        controller.stop("Detenida por vos")
    }

    override fun onStart() {
        runtime.guide.expireOverdueContext()
        super.onStart()
    }

    override fun onStop() {
        if (!runtime.guide.state.value.active) { museum.pause(); controller.stop("App en segundo plano") }
        museumPanel.clearPrivateViews()
        // Lifecycle collection can already be suspended here; clear view-held images directly too.
        preview.setImageDrawable(null); photo.setImageDrawable(null)
        super.onStop()
    }
    private fun prepareVisit() {
        if (!consent.isChecked) return
        if (!controller.state.value.registered || !controller.state.value.activeDevice) { visitStatus.text = "Primero conectá tus lentes desde Configuración y prueba manual"; return }
        pendingVisit = true
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA).fold(onSuccess = { value ->
                if (value == PermissionStatus.Granted) ensureSpeechPermission() else cameraPermission.launch(Permission.CAMERA)
            }, onFailure = { error, _ -> pendingVisit = false; visitStatus.text = "Permiso de cámara: ${error.name}" })
        }
    }

    private fun ensureSpeechPermission() {
        lifecycleScope.launch {
            Wearables.checkPermissionStatus(Permission.MICROPHONE).fold(onSuccess = { value ->
                if (value == PermissionStatus.Granted) ensureNotificationAndStart() else speechPermission.launch(Permission.MICROPHONE)
            }, onFailure = { error, _ -> pendingVisit = false; visitStatus.text = "Escucha de lentes: ${error.name}" })
        }
    }

    private fun ensureNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startPreparedVisit()
    }

    private fun startPreparedVisit() {
        if (!pendingVisit || !consent.isChecked) return
        pendingVisit = false
        if (!VisitService.canNotify(this)) { visitStatus.text = "Habilitá las notificaciones de Luma en Ajustes antes de iniciar una visita."; return }
        runCatching { VisitService.begin(this) }.onFailure { visitStatus.text = it.message ?: "Android no pudo iniciar la visita" }
    }

    override fun onDestroy() { super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("voice_intent_handled", voiceIntentHandled)
        super.onSaveInstanceState(outState)
    }
}
