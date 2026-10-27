package com.lumalive.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit short Bluetooth recording. No PCM is delivered unless the actual route is SCO. */
class GlassesMicrophone(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val finish = AtomicBoolean(false)
    private val recording = Mutex()
    fun finishTurn() { finish.set(true) }

    suspend fun record(onReady: (String) -> Unit): ByteArray = recording.withLock { withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 31) error("Voz Bluetooth de esta demo requiere Android 12+")
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Falta permiso de micrófono" }
        val devices = audio.availableCommunicationDevices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        check(devices.size == 1) { "Conectá solo los lentes como dispositivo de audio Bluetooth. No se usará el micrófono del teléfono." }
        val previousMode = audio.mode
        var recorder: AudioRecord? = null
        finish.set(false)
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            check(audio.setCommunicationDevice(devices.single())) { "No se pudo seleccionar audio de los lentes" }
            withTimeout(5000) {
                while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) delay(50)
            }
            val input = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            checkNotNull(input) { "No hay micrófono Bluetooth; no se capturó el teléfono" }
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "El dispositivo no admite PCM 16 kHz" }
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 8192))
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "No se pudo abrir el micrófono" }
            record.setPreferredDevice(input)
            record.startRecording()
            withTimeout(2000) {
                while (record.routedDevice == null) delay(50)
            }
            check(record.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { "La ruta no es Bluetooth; se descartó la captura" }
            withContext(Dispatchers.Main.immediate) { onReady(input.productName.toString()) }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val deadline = SystemClock.elapsedRealtime() + 12_000
            while (currentCoroutineContext().isActive && !finish.get() && SystemClock.elapsedRealtime() < deadline && output.size() < 384000) {
                check(record.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) { "Cambió el micrófono; audio descartado" }
                val count = record.read(buffer, 0, minOf(buffer.size, 384000 - output.size()), AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Se interrumpió el micrófono Bluetooth" }
                if (count > 0) output.write(buffer, 0, count)
                delay(20)
            }
            currentCoroutineContext().ensureActive()
            check(output.size() >= 3200) { "La pregunta fue demasiado corta o no llegó audio" }
            wav16k(output.toByteArray())
        } finally {
            recorder?.let { record -> runCatching { record.stop() }; record.release() }
            audio.clearCommunicationDevice(); audio.mode = previousMode
        }
    } }
}
