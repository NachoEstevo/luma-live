package com.lumalive.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class ProbeUiState(
    val initialized: Boolean = false,
    val registered: Boolean = false,
    val activeDevice: Boolean = false,
    val registration: String = "Sin inicializar",
    val device: String = "Esperando lentes vinculados en Meta AI",
    val session: String = "Sin sesión",
    val stream: String = "Sin cámara",
    val hasSession: Boolean = false,
    val stopping: Boolean = false,
    val streaming: Boolean = false,
    val capturing: Boolean = false,
    val metrics: String = "Sin mediciones",
    val message: String = "1. Preparar Bluetooth → 2. Vincular con Meta AI → 3. Probar cámara",
    val preview: Bitmap? = null,
    val photo: Bitmap? = null,
)

/** Main-thread owner of DAT. Frame conversion has one worker and no application queue. */
class GlassesCameraController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(ProbeUiState())
    val state = mutableState.asStateFlow()
    private val selector by lazy { AutoDeviceSelector() }
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var sessionScope: CoroutineScope? = null
    private var metrics: ProbeMetrics? = null
    private var generation = 0
    private var stopRequested = false
    private var battery: Int? = null
    private var stopReason = ""

    fun message(text: String) { mutableState.update { it.copy(message = text) } }

    fun initialize(context: Context) {
        if (state.value.initialized) return
        Wearables.initialize(context.applicationContext).fold(
            onSuccess = {
                mutableState.update { it.copy(initialized = true) }
                scope.launch {
                    Wearables.registrationState.collect { value ->
                        Log.i("LumaProbe", "registration:${value.name},dev_mode:${Wearables.isDevMode}")
                        mutableState.update { it.copy(registration = "${value.name} · DevMode=${Wearables.isDevMode}", registered = value == RegistrationState.REGISTERED) }
                        if (value != RegistrationState.REGISTERED && session != null) stop("Registro cambió: ${value.name}")
                    }
                }
                scope.launch {
                    Wearables.registrationErrorStream.collect { message("Registro: ${it.name} · ${it.description}") }
                }
                scope.launch {
                    selector.activeDeviceFlow().collectLatest { id ->
                        mutableState.update { it.copy(activeDevice = id != null) }
                        if (id == null) {
                            battery = null
                            mutableState.update { it.copy(device = "Sin lentes disponibles") }
                        } else {
                            Wearables.devicesMetadata[id]?.collect { device ->
                                Log.i("LumaProbe", "device:link=${device.linkState},compat=${device.compatibility},don=${device.donState},hinge=${device.hingeState},thermal=${device.thermalLevel}")
                                this@GlassesCameraController.battery = device.batteryLevel.takeIf { it in 1..100 }
                                val battery = if (device.batteryLevel > 0) "${device.batteryLevel}%" else "desconocida/vacía"
                                mutableState.update { it.copy(device = "${device.deviceType} · ${device.linkState}\nFirmware ${device.firmwareInfo ?: "no informado"}\nBatería $battery · térmico ${device.thermalLevel}\nUso ${device.donState} · patillas ${device.hingeState}\nCompatibilidad ${device.compatibility}") }
                                if (device.thermalLevel.toString().contains("CRITICAL", true)) stop("Aviso térmico crítico")
                            }
                        }
                    }
                }
            },
            onFailure = { error, _ -> message("Inicialización: ${error.description}") },
        )
    }

    fun start() {
        if (!state.value.registered || !state.value.activeDevice || session != null) return
        generation++
        val run = generation
        stopRequested = false
        metrics = ProbeMetrics(SystemClock.elapsedRealtime(), battery)
        mutableState.update { it.copy(hasSession = true, stopping = false, preview = null, photo = null, message = "Conectando; límite de sesión: 120 s", metrics = "Esperando primer frame") }
        Wearables.createSession(selector).fold(
            onSuccess = { created ->
                session = created
                val jobs = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
                sessionScope = jobs
                jobs.launch(start = CoroutineStart.UNDISPATCHED) {
                    created.errors.collect { error ->
                        Log.i("LumaProbe", "session_error:${error.name}")
                        val detail = "${error.name} · ${error.description}"
                        message(detail)
                        if (error.name != "DWA_OUT_OF_STU_RANGE") {
                            val help = if (error.name == "NO_ELIGIBLE_DEVICE") "No hay un lente conectado y compatible para DAT. Revisá Developer Mode y SDK de los lentes en Meta AI." else detail
                            if (stopRequested) { stopReason = help; message(help) } else stop(help)
                        }
                    }
                }
                jobs.launch(start = CoroutineStart.UNDISPATCHED) {
                    created.state.collect { value ->
                        Log.i("LumaProbe", "session:${value.name}")
                        mutableState.update { it.copy(session = value.name) }
                        when (value) {
                            DeviceSessionState.STARTED -> if (!stopRequested && camera == null) attachCamera(created, jobs, run)
                            DeviceSessionState.STOPPED -> finish(run, stoppedObserved = true)
                            else -> Unit
                        }
                    }
                }
                jobs.launch {
                    delay(20_000)
                    if (metrics?.frames == 0L) stop("Sin primer frame en 20 s: revisá lentes, permisos y versiones")
                }
                jobs.launch {
                    while (isActive) {
                        delay(1000)
                        val now = SystemClock.elapsedRealtime()
                        metrics?.let { m ->
                            mutableState.update { it.copy(metrics = String.format(Locale.US, "%d frames · %.1f fps recibidos\n%d×%d · primer frame %s ms\nSesión %d s · LOW / 7 fps solicitados", m.frames, m.fps(now), m.width, m.height, m.firstFrameMs?.toString() ?: "—", m.elapsed(now) / 1000)) }
                            if (m.elapsed(now) >= 120_000 && !stopRequested) stop("Límite de dos minutos alcanzado")
                        }
                    }
                }
                created.start()
            },
            onFailure = { error, _ ->
                message("Crear sesión: ${error.name} · ${error.description}")
                finish(run)
            },
        )
    }

    private fun attachCamera(created: DeviceSession, jobs: CoroutineScope, run: Int) {
        created.addCamera(StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7, compressVideo = false)).fold(
            onSuccess = { added ->
                camera = added
                val stream = added.stream
                jobs.launch(start = CoroutineStart.UNDISPATCHED) {
                    var wasActive = false
                    stream.state.collect { value ->
                        Log.i("LumaProbe", "stream:${value.name}")
                        mutableState.update { it.copy(stream = value.name, streaming = value == StreamState.STREAMING && !stopRequested) }
                        if (value != StreamState.STOPPED && value != StreamState.CLOSED) wasActive = true
                        if (value == StreamState.CLOSED || (wasActive && value == StreamState.STOPPED)) stop("Cámara detenida; cerrando sesión")
                    }
                }
                jobs.launch(start = CoroutineStart.UNDISPATCHED) {
                    stream.errorStream.collect { error ->
                        Log.i("LumaProbe", "stream_error:${error.name}")
                        message("Video: ${error.name} · ${error.description}")
                        if (error.name == "CRITICAL_STREAM_ERROR") stop("Fallo crítico del stream")
                    }
                }
                jobs.launch(Dispatchers.Default) {
                    var lastPreview = 0L
                    var previewErrorShown = false
                    stream.videoStream.collect { frame ->
                        val now = SystemClock.elapsedRealtime()
                        val byteCount = frame.buffer.remaining()
                        // Copy borrowed buffer before suspending or handing it to another thread.
                        val bytes = if (!frame.isCompressed && now - lastPreview >= 250) ByteArray(byteCount).also { frame.buffer.duplicate().get(it) } else null
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == run && !stopRequested) metrics?.let { m ->
                                m.frame(now, byteCount, frame.width, frame.height)
                                if (m.frames == 1L) Log.i("LumaProbe", "first_frame:ms=${m.firstFrameMs},size=${frame.width}x${frame.height}")
                            }
                        }
                        if (bytes != null) {
                            lastPreview = now
                            try {
                                val bitmap = framePreview(bytes, frame.width, frame.height)
                                withContext(Dispatchers.Main.immediate) {
                                    if (generation == run && !stopRequested) mutableState.update { it.copy(preview = bitmap) }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (!previewErrorShown) withContext(Dispatchers.Main.immediate) { message("Frames recibidos; layout de preview no reconocido. Probá captura fija."); previewErrorShown = true }
                            }
                        }
                    }
                }
                stream.start().onFailure { error, _ -> stop("Iniciar video: ${error.description}") }
            },
            onFailure = { error, _ -> stop("Adjuntar cámara: ${error.description}") },
        )
    }

    fun capture() {
        val jobs = sessionScope ?: return
        if (!state.value.streaming || state.value.capturing || stopRequested) return
        val run = generation
        jobs.launch {
            try {
                val bitmap = captureArtwork()
                if (generation == run && !stopRequested) mutableState.update { it.copy(photo = bitmap, message = "Captura de diagnóstico en memoria. No se envió a nube.") }
            } catch (e: TimeoutCancellationException) {
                message("Captura sin respuesta en 15 s. Detené la sesión antes de intentar de nuevo.")
                stop("Captura agotó su tiempo")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message("No se pudo decodificar la captura")
            }
        }
    }

    suspend fun captureArtwork(): Bitmap {
        val current = checkNotNull(camera) { "Primero activá la cámara de los lentes" }
        check(state.value.streaming && !state.value.capturing && !stopRequested) { "La cámara no está lista o ya está capturando" }
        val run = generation
        mutableState.update { it.copy(capturing = true) }
        try {
            var captured: PhotoData? = null
            var errorText = "No llegó una foto de los lentes"
            withTimeout(15_000) { current.stream.capturePhoto() }
                .onSuccess { captured = it }
                .onFailure { error, _ -> errorText = error.description }
            val photo = checkNotNull(captured) { errorText }
            val bitmap = withContext(Dispatchers.Default) {
                when (photo) {
                    is PhotoData.Bitmap -> photo.bitmap
                    is PhotoData.HEIC -> {
                        val buffer = photo.data.duplicate()
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
            }
            check(generation == run && !stopRequested) { "La sesión terminó durante la captura" }
            Log.i("LumaProbe", if (bitmap != null) "photo:decoded" else "photo:decode_failed")
            return checkNotNull(bitmap) { "Formato de foto no decodificable" }
        } catch (e: TimeoutCancellationException) {
            stop("Captura agotó su tiempo")
            throw e
        } finally {
            if (generation == run) mutableState.update { it.copy(capturing = false) }
        }
    }

    fun stop(reason: String) {
        mutableState.update { it.copy(preview = null, photo = null) }
        if (stopRequested || session == null) return
        stopRequested = true
        stopReason = reason
        Log.i("LumaProbe", "stop_requested")
        metrics?.stop(SystemClock.elapsedRealtime(), battery)
        mutableState.update { it.copy(stopping = true, streaming = false, capturing = false, message = "$reason. Esperando STOPPED del SDK.") }
        camera?.stop()
        session?.stop()
    }

    private fun finish(run: Int, stoppedObserved: Boolean = false) {
        if (run != generation) return
        metrics?.stop(SystemClock.elapsedRealtime(), battery)
        if (stoppedObserved) metrics?.confirmStopped()
        camera = null; session = null
        mutableState.update { it.copy(hasSession = false, stopping = false, streaming = false, capturing = false, preview = null, photo = null, stream = "STOPPED", message = if (stoppedObserved) "${stopReason.ifBlank { "Sesión finalizada" }}. STOPPED confirmado por el SDK; imágenes liberadas de la app." else it.message) }
        sessionScope?.cancel(); sessionScope = null
    }

    fun exportCsv() = metrics?.csv(SystemClock.elapsedRealtime())
    fun close() { stop("App cerrada"); scope.cancel() }
}
