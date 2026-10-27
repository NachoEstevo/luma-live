package com.lumalive.probe

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.*
import android.os.SystemClock
import android.util.Log

/** Shared runtime survives Activity recreation; the visit service owns active background work. */
class LumaApplication : Application() {
    val camera by lazy { GlassesCameraController() }
    val museum by lazy { MuseumConversation(this, camera) }
    val guide by lazy { HandsFreeGuide(SdkVisitExperience(this, camera, museum), appScope, SystemClock::elapsedRealtime, idleSeconds) { Log.i("LumaVisit", it) } }
    val launcher by lazy { VoiceLauncher(::startFromVoice) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voiceStarts by lazy { VoiceLaunchCoordinator(appScope, ::submitVoiceStart) }
    private val options by lazy { getSharedPreferences("visit_options", MODE_PRIVATE) }
    var idleSeconds: Int
        get() = options.getInt("idle_seconds", 25).takeIf { it in setOf(25, 45, 60) } ?: 25
        set(value) { require(value in setOf(25, 45, 60)); options.edit().putInt("idle_seconds", value).apply(); guide.configureIdleWindow(value) }
    var voiceConsent: Boolean
        get() = options.getBoolean("voice_consent_v1", false)
        set(value) { options.edit().putBoolean("voice_consent_v1", value).apply(); if (!value) voiceStarts.cancelPending() }
    private var initialized = false

    override fun onCreate() { super.onCreate(); initializeIfAllowed() }

    fun initializeIfAllowed() {
        if (initialized) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        camera.initialize(this)
        if (camera.state.value.initialized) { initialized = true; launcher.initialize() }
    }

    suspend fun startFromVoice(): Boolean = voiceStarts.request()

    private suspend fun submitVoiceStart(): Boolean {
        if (!voiceConsent) { museum.notice("Abrí Luma y habilitá visitas por voz antes del primer uso."); return false }
        if (guide.state.value.active && guide.state.value.phase !in setOf(VisitPhase.PAUSED, VisitPhase.PAUSING)) return true
        if (!VisitService.canNotify(this)) { museum.notice("Habilitá notificaciones de Luma para poder detener la visita."); return false }
        val ready = withTimeoutOrNull(10_000) { camera.state.first { it.registered && it.activeDevice } } ?: return false
        if (!ready.registered) return false
        var microphoneAllowed = false
        Wearables.checkPermissionStatus(Permission.MICROPHONE).onSuccess { microphoneAllowed = it == PermissionStatus.Granted }
        if (!microphoneAllowed) { museum.notice("Primero autorizá el micrófono desde Preparar e iniciar mi visita."); return false }
        var cameraAllowed = false
        Wearables.checkPermissionStatus(Permission.CAMERA).onSuccess { cameraAllowed = it == PermissionStatus.Granted }
        if (!cameraAllowed) { museum.notice("Primero autorizá la cámara desde Preparar e iniciar mi visita."); return false }
        if (!voiceConsent) return false
        return runCatching { VisitService.begin(this, source = "voice"); true }.getOrElse {
            museum.notice("Android no permitió abrir la visita. Abrí Luma para revisar permisos."); false
        }
    }
}
